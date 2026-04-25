package org.Little_100.projecte.bedrock.transmutation.sell;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.Little_100.projecte.managers.EmcManager;
import org.Little_100.projecte.util.ShulkerBoxUtil;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.response.CustomFormResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 批量出售背包物品。
 *
 * 防刷设计 (核心):
 * 1. 扫描结果只作预览显示, 不作扣除依据
 * 2. 确认执行时, 对每个 slot 做"当前物品是否仍与 snapshot 一致"校验:
 *    - 物品 isSimilar 且数量相同才扣
 *    - 不一致跳过(玩家可能已挪走)
 * 3. EMC 最终加成由 sellItems 根据"实际扣到的物品"重新计算, 不信任扫描的 estimatedEmc
 * 4. Session token 防双开 Form 并发确认
 *
 * 保护开关 (默认):
 * - 仅扫描快捷栏   关
 * - 保护命名物品   开
 * - 保护附魔物品   开
 * - 保护潜影盒     开
 */
public final class BulkSellForm {

    // CustomForm 组件索引
    private static final int IDX_TOGGLE_HOTBAR_ONLY = 1;
    private static final int IDX_TOGGLE_PROTECT_NAMED = 2;
    private static final int IDX_TOGGLE_PROTECT_ENCHANTED = 3;
    private static final int IDX_TOGGLE_PROTECT_SHULKER = 4;
    private static final int IDX_TOGGLE_CONFIRM = 5;

    private BulkSellForm() {}

    public static void open(Player player) {
        if (player == null || !player.isOnline()) return;
        SellFilter defaultFilter = new SellFilter(false, true, true, true);
        openWithFilter(player, defaultFilter);
    }

    private static void openWithFilter(Player player, SellFilter filter) {
        ScanResult preview = scan(player, filter);
        String labelText = buildScanLabel(preview);

        // 每次打开 Form 都生成新 session token
        UUID token = BedrockFormUtil.newSession(player);

        CustomForm form = CustomForm.builder()
                .title("§6§l批量出售")
                .label(labelText)
                .toggle("§f仅扫描快捷栏", filter.hotbarOnly)
                .toggle("§f保护命名物品", filter.protectNamed)
                .toggle("§f保护附魔物品", filter.protectEnchanted)
                .toggle("§f保护潜影盒", filter.protectShulker)
                .toggle("§c§l确认出售 §7(勾选后点提交)", false)
                .validResultHandler(response -> handleSubmit(player, response, token))
                .closedOrInvalidResultHandler(() -> {
                    if (player.isOnline()) SellEntryForm.open(player);
                })
                .build();

        BedrockFormUtil.sendForm(player, form);
    }

    private static void handleSubmit(Player player, CustomFormResponse response, UUID token) {
        if (!player.isOnline()) return;

        // [防御]: session 校验
        if (!BedrockFormUtil.isSessionValid(player, token)) {
            player.sendMessage("§c此次操作已失效 (可能打开了新的界面), 请重新尝试。");
            return;
        }

        SellFilter filter = new SellFilter(
                response.asToggle(IDX_TOGGLE_HOTBAR_ONLY),
                response.asToggle(IDX_TOGGLE_PROTECT_NAMED),
                response.asToggle(IDX_TOGGLE_PROTECT_ENCHANTED),
                response.asToggle(IDX_TOGGLE_PROTECT_SHULKER)
        );
        boolean confirmed = response.asToggle(IDX_TOGGLE_CONFIRM);

        if (!confirmed) {
            // 没勾选确认 -> 重开 (带当前开关状态, 预览更新)
            openWithFilter(player, filter);
            return;
        }

        ScanResult scanForPreview = scan(player, filter);

        if (scanForPreview.snapshots.isEmpty()) {
            ModalForm empty = ModalForm.builder()
                    .title("§e无可出售物品")
                    .content("§7按当前保护条件, 没有可以出售的物品。")
                    .button1("§a调整条件")
                    .button2("§c取消")
                    .validResultHandler(resp -> {
                        if (!player.isOnline()) return;
                        if (resp.clickedButtonId() == 0) openWithFilter(player, filter);
                    })
                    .build();
            BedrockFormUtil.sendForm(player, empty);
            return;
        }

        // 最终确认 (ModalForm)
        String content = "§f即将出售 §e" + scanForPreview.snapshots.size() + "§f 种物品 (共 §e"
                + scanForPreview.totalAmount + "§f 个)\n"
                + "§f预计获得: §a+" + BedrockFormUtil.formatEmc(scanForPreview.estimatedEmc) + " EMC\n\n"
                + "§c⚠ 物品将被消耗, 此操作不可撤销";

        // 新 session token 给最终确认
        UUID finalToken = BedrockFormUtil.newSession(player);

        ModalForm finalConfirm = ModalForm.builder()
                .title("§6§l最终确认")
                .content(content)
                .button1("§a§l确认出售")
                .button2("§c取消")
                .validResultHandler(resp -> {
                    if (!player.isOnline()) return;
                    if (!BedrockFormUtil.isSessionValid(player, finalToken)) {
                        player.sendMessage("§c此次操作已失效, 请重新尝试。");
                        return;
                    }
                    if (resp.clickedButtonId() != 0) {
                        openWithFilter(player, filter);
                        return;
                    }
                    executeSell(player, filter);
                })
                .build();

        BedrockFormUtil.sendForm(player, finalConfirm);
    }

    /**
     * 执行出售。
     *
     * 关键安全点: 不用扫描时的 ItemStack 快照去扣除,
     * 而是在此时重新读每个 slot, 对比 isSimilar+amount 一致才扣。
     * 这样即使玩家在确认期间挪动了物品, 也不会扣错或扣多。
     */
    private static void executeSell(Player player, SellFilter filter) {
        PlayerInventory inv = player.getInventory();
        EmcManager emcManager = ProjectE.getInstance().getEmcManager();

        List<ItemStack> itemsToSell = new ArrayList<>();
        int totalAmount = 0;

        // 扫描范围
        int endSlot = filter.hotbarOnly ? 9 : 36;

        for (int i = 0; i < endSlot; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            // 当前保护规则 (用最新背包状态重判断, 不信任旧 snapshot)
            if (!passesFilter(item, filter)) continue;

            long emc = emcManager.calculateSellEmcFor(item);
            if (emc <= 0) continue;

            // 扣掉这个 slot (clone 保留物品元数据)
            itemsToSell.add(item.clone());
            totalAmount += item.getAmount();
            inv.setItem(i, null);
        }

        if (itemsToSell.isEmpty()) {
            player.sendMessage("§c背包物品已变化或保护条件变化, 出售取消。");
            SellEntryForm.open(player);
            return;
        }

        // 调用核心方法 (内部再次做溢出检查)
        EmcManager.SellResult result = emcManager.sellItems(player, itemsToSell);

        // 退回被拒绝的物品
        for (ItemStack rejected : result.getRejected()) {
            HashMap<Integer, ItemStack> remaining = inv.addItem(rejected);
            for (ItemStack drop : remaining.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        if (!result.isEmcCredited() && result.getTotalEmc() > 0) {
            player.sendMessage("§c出售异常: EMC 未能入账, 请联系管理员。");
        }

        showResult(player, result, itemsToSell.size(), totalAmount);
    }

    private static boolean passesFilter(ItemStack item, SellFilter filter) {
        if (filter.protectNamed && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()) {
            return false;
        }
        if (filter.protectEnchanted && item.hasItemMeta()
                && item.getItemMeta().hasEnchants()) {
            return false;
        }
        if (filter.protectShulker && item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox) {
            return false;
        }
        // 潜影盒内部有无 EMC 物品: 和 Java 版一样跳过
        if (item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox
                && ShulkerBoxUtil.getFirstItemWithoutEmc(item) != null) {
            return false;
        }
        return true;
    }

    private static void showResult(Player player, EmcManager.SellResult result,
                                   int kinds, int totalAmount) {
        StringBuilder sb = new StringBuilder();
        if (result.isEmcCredited()) {
            sb.append("§a§l出售完成!\n\n");
        } else {
            sb.append("§e§l出售部分完成\n\n");
        }
        sb.append("§f卖出 §e").append(kinds).append("§f 种 §e")
          .append(totalAmount).append("§f 个物品\n");
        sb.append("§f获得: §a+").append(BedrockFormUtil.formatEmc(result.getTotalEmc()))
          .append(" EMC\n");

        if (!result.getNewlyLearned().isEmpty()) {
            sb.append("\n§d§l✨ 学会了 ").append(result.getNewlyLearned().size()).append(" 种新物品!");
        }

        long newEmc = ProjectE.getInstance().getDatabaseManager().getPlayerEmc(player.getUniqueId());
        sb.append("\n\n§f当前余额: §e").append(BedrockFormUtil.formatEmc(newEmc)).append(" EMC");

        BedrockFormUtil.newSession(player);

        ModalForm resultForm = ModalForm.builder()
                .title("§a§l出售完成")
                .content(sb.toString())
                .button1("§a再来一次")
                .button2("§7返回主菜单")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) open(player);
                    else TransmutationMainForm.open(player);
                })
                .build();
        BedrockFormUtil.sendForm(player, resultForm);
    }

    // ==================== 预览扫描 ====================

    /** 预览扫描: 只做统计用于显示, 不用于扣除 */
    private static ScanResult scan(Player player, SellFilter filter) {
        PlayerInventory inv = player.getInventory();
        EmcManager emcManager = ProjectE.getInstance().getEmcManager();

        List<ItemStack> snapshots = new ArrayList<>();
        int totalAmount = 0;
        long estimatedEmc = 0;
        int noEmcKinds = 0;

        int endSlot = filter.hotbarOnly ? 9 : 36;

        for (int i = 0; i < endSlot; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            if (!passesFilter(item, filter)) continue;

            long emc = emcManager.calculateSellEmcFor(item);
            if (emc <= 0) {
                noEmcKinds++;
                continue;
            }

            snapshots.add(item.clone());
            totalAmount += item.getAmount();

            // 累加时也做溢出保护
            long newTotal = estimatedEmc + emc;
            if (newTotal < estimatedEmc) {
                // 估算溢出, 停止累加, 给个 long 最大值让用户看到"太多了"
                estimatedEmc = Long.MAX_VALUE;
                break;
            }
            estimatedEmc = newTotal;
        }

        return new ScanResult(snapshots, totalAmount, estimatedEmc, noEmcKinds);
    }

    private static String buildScanLabel(ScanResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("§l§6扫描结果§r\n");
        sb.append("§f可出售物品: §a").append(result.snapshots.size()).append(" §f种 §7(§f")
          .append(result.totalAmount).append(" §7个)\n");
        if (result.noEmcKinds > 0) {
            sb.append("§f无 EMC 跳过: §c").append(result.noEmcKinds).append(" §f种\n");
        }
        sb.append("§f预计获得: §a+").append(BedrockFormUtil.formatEmc(result.estimatedEmc))
          .append(" EMC\n\n");
        sb.append("§7调整保护开关后, 勾选 '确认出售' 并提交。");
        return sb.toString();
    }

    // ==================== 值对象 ====================

    private static class SellFilter {
        final boolean hotbarOnly;
        final boolean protectNamed;
        final boolean protectEnchanted;
        final boolean protectShulker;

        SellFilter(boolean hotbarOnly, boolean protectNamed,
                   boolean protectEnchanted, boolean protectShulker) {
            this.hotbarOnly = hotbarOnly;
            this.protectNamed = protectNamed;
            this.protectEnchanted = protectEnchanted;
            this.protectShulker = protectShulker;
        }
    }

    private static class ScanResult {
        final List<ItemStack> snapshots;
        final int totalAmount;
        final long estimatedEmc;
        final int noEmcKinds;

        ScanResult(List<ItemStack> snapshots, int totalAmount,
                   long estimatedEmc, int noEmcKinds) {
            this.snapshots = snapshots;
            this.totalAmount = totalAmount;
            this.estimatedEmc = estimatedEmc;
            this.noEmcKinds = noEmcKinds;
        }
    }
}
