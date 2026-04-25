package org.Little_100.projecte.bedrock.transmutation.buy;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.managers.EmcManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.CustomForm;

/**
 * 自定义购买数量。
 *
 * 防刷:
 * - slider 上限受 MAX_BUY_AMOUNT (EmcManager.MAX_BUY_AMOUNT=576) 和 maxAffordable 双重约束
 * - 回调接收数量后再 clamp 一次, 防止客户端恶意伪造超限值
 * - EMC 扣除最终仍在 EmcManager.buyItem 执行, 那里还有一次完整校验
 */
public final class BuyAmountForm {

    private static final int IDX_SLIDER = 1;

    private BuyAmountForm() {}

    public static void open(Player player, String itemKey, int returnPage, String returnFilter) {
        if (player == null || !player.isOnline()) return;
        ProjectE plugin = ProjectE.getInstance();

        ItemStack sample = plugin.getItemStackFromKey(itemKey);
        if (sample == null) {
            player.sendMessage("§c物品不可用");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }

        long unitEmc = plugin.getEmcManager().getEmc(itemKey);
        long playerEmc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());

        if (unitEmc <= 0) {
            player.sendMessage("§c该物品无 EMC 值");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }

        // 计算上限: min(MAX_BUY_AMOUNT, playerEmc/unitEmc, maxStack*9)
        int maxAffordable = (int) Math.min(playerEmc / unitEmc, EmcManager.MAX_BUY_AMOUNT);
        maxAffordable = Math.min(maxAffordable, sample.getMaxStackSize() * 9);

        if (maxAffordable < 1) {
            player.sendMessage("§cEMC 不足, 连 1 个都买不起。");
            BuyChoiceForm.open(player, itemKey, returnPage, returnFilter);
            return;
        }

        String itemName = BedrockFormUtil.getDisplayName(sample);
        String labelText = "§f物品: §e" + itemName + "\n"
                + "§f单价: §e" + BedrockFormUtil.formatEmc(unitEmc) + " EMC\n"
                + "§f你的 EMC: §e" + BedrockFormUtil.formatEmc(playerEmc) + "\n"
                + "§f最多可买: §e" + maxAffordable + " §f个\n\n"
                + "§7拖动滑块选择数量:";

        final int max = maxAffordable;
        CustomForm form = CustomForm.builder()
                .title("§b§l购买 " + itemName)
                .label(labelText)
                .slider("数量", 1, max, 1, 1)
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;

                    int amount = (int) response.asSlider(IDX_SLIDER);
                    // [防御]: clamp, 防客户端发送超限值
                    if (amount < 1) amount = 1;
                    if (amount > max) amount = max;
                    if (amount > EmcManager.MAX_BUY_AMOUNT) amount = EmcManager.MAX_BUY_AMOUNT;

                    BuyConfirmForm.openConfirm(player, itemKey, amount, returnPage, returnFilter);
                })
                .closedOrInvalidResultHandler(() -> {
                    if (player.isOnline()) {
                        BuyChoiceForm.open(player, itemKey, returnPage, returnFilter);
                    }
                })
                .build();

        BedrockFormUtil.sendForm(player, form);
    }
}
