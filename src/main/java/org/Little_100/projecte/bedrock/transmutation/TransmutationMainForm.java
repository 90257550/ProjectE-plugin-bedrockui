package org.Little_100.projecte.bedrock.transmutation;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.buy.BuyListForm;
import org.Little_100.projecte.bedrock.transmutation.sell.SellEntryForm;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;

import java.util.UUID;

/**
 * 基岩版转换桌主菜单。入口: /projecte opentable 或 /opentable
 */
public final class TransmutationMainForm {

    private TransmutationMainForm() {}

    public static void open(Player player) {
        if (player == null || !player.isOnline()) return;

        UUID token = BedrockFormUtil.newSession(player);

        long emc = ProjectE.getInstance().getDatabaseManager().getPlayerEmc(player.getUniqueId());

        SimpleForm form = SimpleForm.builder()
                .title("§5§l转换桌")
                .content("§f你的 EMC: §e" + BedrockFormUtil.formatEmc(emc) + "\n\n§7请选择操作:")
                .button("§a§l出售物品\n§7将物品转换为 EMC")
                .button("§b§l购买物品\n§7使用 EMC 获取已学习的物品")
                .button("§c关闭")
                .validResultHandler(response -> {
                    // 主菜单不需要 session 检查(顶层入口), 但子菜单需要
                    if (!player.isOnline()) return;
                    switch (response.clickedButtonId()) {
                        case 0 -> SellEntryForm.open(player);
                        case 1 -> BuyListForm.open(player, 0, "");
                        case 2 -> { /* 关闭 */ }
                    }
                })
                .build();

        BedrockFormUtil.sendForm(player, form);
    }
}
