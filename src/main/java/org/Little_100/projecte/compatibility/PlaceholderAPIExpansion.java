package org.Little_100.projecte.compatibility;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Little_100.projecte.ProjectE;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI扩展类
 * 提供ProjectE相关的变量支持
 */
public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private final ProjectE plugin;

    public PlaceholderAPIExpansion(ProjectE plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "projecte";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        // %projecte_emc% - 返回玩家的EMC值
        if (params.equalsIgnoreCase("emc")) {
            long emc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());
            return String.valueOf(emc);
        }

        // %projecte_emc_formatted% - 返回格式化的EMC值(带千位分隔符)
        if (params.equalsIgnoreCase("emc_formatted")) {
            long emc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());
            return String.format("%,d", emc);
        }

        // %projecte_emc_short% - 返回简短格式的EMC值(如: 1.5K, 2.3M)
        if (params.equalsIgnoreCase("emc_short")) {
            long emc = plugin.getDatabaseManager().getPlayerEmc(player.getUniqueId());
            return formatShortNumber(emc);
        }

        return null;
    }

    /**
     * 将数字格式化为简短形式
     * @param number 要格式化的数字
     * @return 格式化后的字符串
     */
    private String formatShortNumber(long number) {
        if (number < 1000) {
            return String.valueOf(number);
        } else if (number < 1000000) {
            return String.format("%.1fK", number / 1000.0);
        } else if (number < 1000000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number < 1000000000000L) {
            return String.format("%.1fB", number / 1000000000.0);
        } else {
            return String.format("%.1fT", number / 1000000000000.0);
        }
    }
}