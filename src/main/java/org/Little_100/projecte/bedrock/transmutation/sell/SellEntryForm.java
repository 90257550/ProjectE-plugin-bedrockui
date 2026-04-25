package org.Little_100.projecte.bedrock.transmutation.sell;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;

public final class SellEntryForm {

    private SellEntryForm() {}

    public static void open(Player player) {
        if (player == null || !player.isOnline()) return;

        long emc = ProjectE.getInstance().getDatabaseManager().getPlayerEmc(player.getUniqueId());

        SimpleForm form = SimpleForm.builder()
                .title("§a§l出售物品")
                .content("§f你的 EMC: §e" + BedrockFormUtil.formatEmc(emc) + "\n\n§7选择出售方式:")
                .button("§e§l快速出售手持物品\n§7出售你主手持有的物品")
                .button("§6§l批量出售背包物品\n§7扫描背包按条件批量出售")
                .button("§7返回主菜单")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    switch (response.clickedButtonId()) {
                        case 0 -> QuickSellForm.open(player);
                        case 1 -> BulkSellForm.open(player);
                        case 2 -> TransmutationMainForm.open(player);
                    }
                })
                .build();

        BedrockFormUtil.sendForm(player, form);
    }
}
