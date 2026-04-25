package org.Little_100.projecte.bedrock.transmutation.buy;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;

/**
 * 购买数量选择 (三按钮 + 自定义)。
 *
 * 按钮:
 * - 买 1 个
 * - 买 1 组 (maxStack)
 * - 自定义数量...
 * - 返回
 *
 * 贤者之石特判: 强制 1 个, 已有则不允许打开
 *
 * 防刷说明:
 * - 本 Form 只做选择, 真正 EMC 扣除在 BuyConfirmForm -> EmcManager.buyItem
 * - 溢出检查也统一在 EmcManager.buyItem 里做
 */
public final class BuyChoiceForm {

    private BuyChoiceForm() {}

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
        if (unitEmc <= 0) {
            player.sendMessage("§c该物品无 EMC 值");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }

        long playerEmc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());
        int maxStack = sample.getMaxStackSize();

        // 贤者之石特判
        boolean isPhilStone = plugin.isPhilosopherStone(sample);
        if (isPhilStone) {
            if (player.getInventory().containsAtLeast(plugin.getPhilosopherStone(), 1)) {
                ModalForm already = ModalForm.builder()
                        .title("§c已拥有贤者之石")
                        .content("§7你已经拥有贤者之石, 不能再购买一个。")
                        .button1("§a返回")
                        .button2("§c关闭")
                        .validResultHandler(response -> {
                            if (!player.isOnline()) return;
                            if (response.clickedButtonId() == 0) {
                                BuyListForm.open(player, returnPage, returnFilter);
                            }
                        })
                        .build();
                BedrockFormUtil.sendForm(player, already);
                return;
            }
            // 贤者之石直接进最终确认 (只能 1 个)
            if (playerEmc < unitEmc) {
                notEnoughAlert(player, unitEmc, playerEmc, returnPage, returnFilter);
                return;
            }
            BuyConfirmForm.openConfirm(player, itemKey, 1, returnPage, returnFilter);
            return;
        }

        // 普通物品: 3 按钮
        long costOne = unitEmc;
        // 买一组的成本: 溢出检查 (unitEmc * maxStack)
        long costStack;
        if (unitEmc > Long.MAX_VALUE / maxStack) {
            costStack = Long.MAX_VALUE; // 会被 canAffordStack 判定为 false
        } else {
            costStack = unitEmc * maxStack;
        }

        boolean canAffordOne = playerEmc >= costOne;
        boolean canAffordStack = playerEmc >= costStack;

        String itemName = BedrockFormUtil.getDisplayName(sample);
        String content = "§f物品: §e" + itemName + "\n"
                + "§f单价: §e" + BedrockFormUtil.formatEmc(unitEmc) + " EMC\n"
                + "§f你的 EMC: §e" + BedrockFormUtil.formatEmc(playerEmc) + "\n\n"
                + "§7请选择购买数量:";

        SimpleForm.Builder builder = SimpleForm.builder()
                .title("§b§l购买 " + itemName)
                .content(content);

        // 按钮 0: 买 1
        if (canAffordOne) {
            builder.button("§a买 1 个\n§7-" + BedrockFormUtil.formatEmc(costOne) + " EMC");
        } else {
            builder.button("§8买 1 个 §c(EMC 不足)");
        }

        // 按钮 1: 买 1 组
        if (canAffordStack) {
            builder.button("§a买 1 组 (" + maxStack + " 个)\n§7-" + BedrockFormUtil.formatEmc(costStack) + " EMC");
        } else {
            builder.button("§8买 1 组 §c(EMC 不足)");
        }

        // 按钮 2: 自定义
        builder.button("§e自定义数量...");

        // 按钮 3: 返回
        builder.button("§7返回列表");

        final boolean finalCanAffordOne = canAffordOne;
        final boolean finalCanAffordStack = canAffordStack;
        final int finalMaxStack = maxStack;

        builder.validResultHandler(response -> {
            if (!player.isOnline()) return;
            switch (response.clickedButtonId()) {
                case 0 -> {
                    if (finalCanAffordOne) {
                        BuyConfirmForm.openConfirm(player, itemKey, 1, returnPage, returnFilter);
                    } else {
                        BuyListForm.open(player, returnPage, returnFilter);
                    }
                }
                case 1 -> {
                    if (finalCanAffordStack) {
                        BuyConfirmForm.openConfirm(player, itemKey, finalMaxStack, returnPage, returnFilter);
                    } else {
                        BuyListForm.open(player, returnPage, returnFilter);
                    }
                }
                case 2 -> BuyAmountForm.open(player, itemKey, returnPage, returnFilter);
                case 3 -> BuyListForm.open(player, returnPage, returnFilter);
            }
        });

        BedrockFormUtil.sendForm(player, builder.build());
    }

    private static void notEnoughAlert(Player player, long unitEmc, long playerEmc,
                                       int returnPage, String returnFilter) {
        ModalForm notEnough = ModalForm.builder()
                .title("§cEMC 不足")
                .content("§f贤者之石价格: §e" + BedrockFormUtil.formatEmc(unitEmc) + " EMC\n"
                        + "§f你的 EMC: §e" + BedrockFormUtil.formatEmc(playerEmc) + "\n\n"
                        + "§c你的 EMC 不足以购买贤者之石。")
                .button1("§a返回")
                .button2("§c关闭")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) {
                        BuyListForm.open(player, returnPage, returnFilter);
                    }
                })
                .build();
        BedrockFormUtil.sendForm(player, notEnough);
    }
}
