package org.Little_100.projecte.bedrock.transmutation.buy;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.Little_100.projecte.managers.EmcManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.ModalForm;

import java.util.UUID;

/**
 * 购买最终确认。
 *
 * 防刷核心:
 * 1. Session token 防并发 Form 双重购买
 * 2. 真正的 EMC 扣除和物品发放由 EmcManager.buyItem 独立校验执行:
 *    - 内部重新查 playerEmc, 防止 UI 显示陈旧
 *    - 内部做溢出检查
 *    - 内部做贤者之石二次购买检查
 *    - UI 显示的预览只是展示, 不影响真实操作
 */
public final class BuyConfirmForm {

    private BuyConfirmForm() {}

    public static void openConfirm(Player player, String itemKey, int amount,
                                   int returnPage, String returnFilter) {
        if (player == null || !player.isOnline()) return;
        ProjectE plugin = ProjectE.getInstance();

        // 参数范围校验 (UI 第一道防线)
        if (amount < 1 || amount > EmcManager.MAX_BUY_AMOUNT) {
            player.sendMessage("§c数量无效");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }

        ItemStack sample = plugin.getItemStackFromKey(itemKey);
        if (sample == null) {
            player.sendMessage("§c物品不可用");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }

        long unitEmc = plugin.getEmcManager().getEmc(itemKey);
        long playerEmc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());

        // 溢出检查
        if (unitEmc <= 0 || amount > Long.MAX_VALUE / unitEmc) {
            player.sendMessage("§c购买金额异常");
            BuyListForm.open(player, returnPage, returnFilter);
            return;
        }
        long totalCost = unitEmc * amount;

        if (playerEmc < totalCost) {
            showNotEnough(player, returnPage, returnFilter);
            return;
        }

        String itemName = BedrockFormUtil.getDisplayName(sample);
        String content = "§f物品: §e" + itemName + "\n"
                + "§f数量: §e" + amount + "\n"
                + "§f单价: §e" + BedrockFormUtil.formatEmc(unitEmc) + " EMC\n"
                + "§f总价: §c-" + BedrockFormUtil.formatEmc(totalCost) + " EMC\n\n"
                + "§f购买前: §e" + BedrockFormUtil.formatEmc(playerEmc) + " EMC\n"
                + "§f购买后: §e" + BedrockFormUtil.formatEmc(playerEmc - totalCost) + " EMC\n\n"
                + "§7确认购买吗?";

        // 生成 session token 绑定此次确认
        UUID token = BedrockFormUtil.newSession(player);

        ModalForm confirm = ModalForm.builder()
                .title("§6§l确认购买")
                .content(content)
                .button1("§a§l确认购买")
                .button2("§c取消")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;

                    // Session 校验
                    if (!BedrockFormUtil.isSessionValid(player, token)) {
                        player.sendMessage("§c此次购买已失效 (可能打开了新的界面), 请重新尝试。");
                        return;
                    }

                    if (response.clickedButtonId() != 0) {
                        BuyChoiceForm.open(player, itemKey, returnPage, returnFilter);
                        return;
                    }

                    // 执行购买 (EmcManager.buyItem 内部会再次校验一切)
                    EmcManager.BuyResult result = plugin.getEmcManager()
                            .buyItem(player, itemKey, amount);

                    showResult(player, result, itemName, returnPage, returnFilter);
                })
                .build();

        BedrockFormUtil.sendForm(player, confirm);
    }

    private static void showResult(Player player, EmcManager.BuyResult result,
                                   String itemName, int returnPage, String returnFilter) {
        String title;
        String content;

        switch (result.getStatus()) {
            case SUCCESS -> {
                long newEmc = ProjectE.getInstance().getDatabaseManager()
                        .getPlayerEmc(player.getUniqueId());
                title = "§a§l购买成功";
                content = "§f物品: §e" + itemName + " §7x " + result.getActualAmount() + "\n"
                        + "§f花费: §c-" + BedrockFormUtil.formatEmc(result.getCost()) + " EMC\n\n"
                        + "§f当前余额: §e" + BedrockFormUtil.formatEmc(newEmc) + " EMC";
            }
            case NOT_ENOUGH_EMC -> {
                title = "§c购买失败";
                content = "§7EMC 不足。需要 §e" + BedrockFormUtil.formatEmc(result.getCost()) + " §7EMC。";
            }
            case ITEM_NOT_AVAILABLE -> {
                title = "§c购买失败";
                content = "§7该物品无法生成, 可能是插件数据问题。请联系管理员。";
            }
            case ALREADY_OWNED -> {
                title = "§c购买失败";
                content = "§7你已经拥有贤者之石, 不能再买一个。";
            }
            case INVALID_AMOUNT -> {
                title = "§c购买失败";
                content = "§7数量无效或金额溢出。";
            }
            default -> {
                title = "§c购买失败";
                content = "§7未知错误。";
            }
        }

        // 新 session
        BedrockFormUtil.newSession(player);

        ModalForm resultForm = ModalForm.builder()
                .title(title)
                .content(content)
                .button1("§a继续购物")
                .button2("§7返回主菜单")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) {
                        BuyListForm.open(player, returnPage, returnFilter);
                    } else {
                        TransmutationMainForm.open(player);
                    }
                })
                .build();

        BedrockFormUtil.sendForm(player, resultForm);
    }

    private static void showNotEnough(Player player, int returnPage, String returnFilter) {
        ModalForm form = ModalForm.builder()
                .title("§cEMC 不足")
                .content("§7你的 EMC 不足以完成购买。")
                .button1("§a返回")
                .button2("§c关闭")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) {
                        BuyListForm.open(player, returnPage, returnFilter);
                    }
                })
                .build();
        BedrockFormUtil.sendForm(player, form);
    }
}
