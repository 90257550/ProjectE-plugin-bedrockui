package org.Little_100.projecte.bedrock.transmutation.sell;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.Little_100.projecte.managers.EmcManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.ModalForm;

import java.util.List;
import java.util.UUID;

/**
 * 快速出售手持物品。
 *
 * 防刷设计:
 * - Session token 防双开 Form 并发点确认
 * - 确认回调时重新读主手物品, 和打开时的 snapshot 比对:
 *   - 物品类型 isSimilar
 *   - 数量一致
 *   若不一致(玩家在确认期间换了物品), 拒绝出售。
 * - EMC 计算由 EmcManager.sellItems 内部再做一次, UI 的预估值仅作显示。
 */
public final class QuickSellForm {

    private QuickSellForm() {}

    public static void open(Player player) {
        if (player == null || !player.isOnline()) return;

        ItemStack inHand = player.getInventory().getItemInMainHand();

        if (inHand == null || inHand.getType().isAir()) {
            showSimpleAlert(player, "§c无法出售",
                    "§7你的主手上没有任何物品。\n§7请先拿起一个想出售的物品再来。",
                    () -> SellEntryForm.open(player));
            return;
        }

        EmcManager emcManager = ProjectE.getInstance().getEmcManager();
        long estimatedEmc = emcManager.calculateSellEmcFor(inHand);

        if (estimatedEmc <= 0) {
            showSimpleAlert(player, "§c物品无 EMC 值",
                    "§f" + BedrockFormUtil.getDisplayName(inHand)
                            + "§7 没有 EMC 值, 无法出售。\n\n"
                            + "§7(若为潜影盒, 请检查内部物品是否全部有 EMC 值)",
                    () -> SellEntryForm.open(player));
            return;
        }

        // 做一份 snapshot 用于确认时比对
        ItemStack snapshot = inHand.clone();
        int snapshotAmount = inHand.getAmount();
        long currentEmc = ProjectE.getInstance().getDatabaseManager().getPlayerEmc(player.getUniqueId());

        String content = "§f物品: §e" + BedrockFormUtil.getDisplayName(inHand) + "\n"
                + "§f数量: §e" + snapshotAmount + "\n"
                + "§f预计获得: §a+" + BedrockFormUtil.formatEmc(estimatedEmc) + " EMC\n"
                + "§f出售后余额: §e~" + BedrockFormUtil.formatEmc(currentEmc + estimatedEmc) + " EMC\n\n"
                + "§c⚠ 物品将被消耗, 此操作不可撤销";

        // 生成 session token 绑定此次确认
        UUID token = BedrockFormUtil.newSession(player);

        ModalForm confirm = ModalForm.builder()
                .title("§6§l确认出售")
                .content(content)
                .button1("§a§l确认出售")
                .button2("§c取消")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;

                    // [防御 3]: Session token 校验, 防重放/双击
                    if (!BedrockFormUtil.isSessionValid(player, token)) {
                        player.sendMessage("§c此次出售已失效 (可能打开了新的界面), 请重新尝试。");
                        return;
                    }

                    if (response.clickedButtonId() != 0) {
                        SellEntryForm.open(player);
                        return;
                    }

                    // [防御 1]: 重新取主手, 校验和 snapshot 一致
                    ItemStack current = player.getInventory().getItemInMainHand();
                    if (current == null || current.getType().isAir()
                            || !current.isSimilar(snapshot)
                            || current.getAmount() != snapshotAmount) {
                        player.sendMessage("§c主手物品已变化, 出售取消 (防作弊保护)。");
                        SellEntryForm.open(player);
                        return;
                    }

                    // 执行: 先扣主手物品, 再调 sellItems
                    player.getInventory().setItemInMainHand(null);
                    EmcManager.SellResult result = ProjectE.getInstance()
                            .getEmcManager()
                            .sellItems(player, List.of(current));

                    // 退回被拒绝的 (极端情况)
                    for (ItemStack rejected : result.getRejected()) {
                        java.util.HashMap<Integer, ItemStack> remaining =
                                player.getInventory().addItem(rejected);
                        for (ItemStack drop : remaining.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }

                    if (!result.isEmcCredited() && result.getTotalEmc() > 0) {
                        player.sendMessage("§c出售异常: EMC 未能入账, 请联系管理员。");
                    }

                    showResult(player, result, current, snapshotAmount);
                })
                .build();

        BedrockFormUtil.sendForm(player, confirm);
    }

    private static void showResult(Player player, EmcManager.SellResult result,
                                   ItemStack soldItem, int amount) {
        StringBuilder sb = new StringBuilder();
        if (result.getTotalEmc() > 0 && result.isEmcCredited()) {
            sb.append("§a出售成功!\n\n");
        } else {
            sb.append("§e出售部分完成\n\n");
        }
        sb.append("§f物品: §e").append(BedrockFormUtil.getDisplayName(soldItem))
          .append(" §7x ").append(amount).append("\n");
        sb.append("§f获得: §a+").append(BedrockFormUtil.formatEmc(result.getTotalEmc()))
          .append(" EMC\n");

        if (!result.getNewlyLearned().isEmpty()) {
            sb.append("\n§d§l✨ 学会了 ").append(result.getNewlyLearned().size()).append(" 种新物品!");
        }

        long newEmc = ProjectE.getInstance().getDatabaseManager().getPlayerEmc(player.getUniqueId());
        sb.append("\n\n§f当前余额: §e").append(BedrockFormUtil.formatEmc(newEmc)).append(" EMC");

        // 生成新 session
        BedrockFormUtil.newSession(player);

        ModalForm resultForm = ModalForm.builder()
                .title("§a§l出售完成")
                .content(sb.toString())
                .button1("§a继续出售")
                .button2("§7返回主菜单")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) {
                        SellEntryForm.open(player);
                    } else {
                        TransmutationMainForm.open(player);
                    }
                })
                .build();

        BedrockFormUtil.sendForm(player, resultForm);
    }

    private static void showSimpleAlert(Player player, String title, String content, Runnable onBack) {
        ModalForm form = ModalForm.builder()
                .title(title)
                .content(content)
                .button1("§a返回")
                .button2("§c关闭")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0 && onBack != null) {
                        onBack.run();
                    }
                })
                .build();
        BedrockFormUtil.sendForm(player, form);
    }
}
