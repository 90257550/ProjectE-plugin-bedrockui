package org.Little_100.projecte.bedrock;

import org.Little_100.projecte.ProjectE;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 基岩版 Form UI 的通用工具类。
 *
 * 架构说明:
 *   Velocity 代理 + Floodgate 后端模式下, 我们的插件跑在后端,
 *   后端没有 Geyser API, 但有 Floodgate API。
 *   所以用 FloodgateApi 来判断玩家是否是基岩版玩家。
 *   发送 Form 用 Cumulus (Cumulus 在 Geyser/Floodgate 任何一方都能工作)。
 *
 * 职责:
 *   - 检测玩家是否是 Floodgate 识别的基岩版玩家
 *   - 安全地发送 Cumulus 表单
 *   - 维护玩家的 Form Session Token, 防止 Form 重放/双击攻击
 */
public final class BedrockFormUtil {

    private BedrockFormUtil() {}

    // ==================== Floodgate / Cumulus 检测 ====================

    private static Boolean floodgateApiAvailable = null;

    public static boolean isFloodgateApiAvailable() {
        if (floodgateApiAvailable == null) {
            try {
                Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApiAvailable = true;
            } catch (ClassNotFoundException e) {
                floodgateApiAvailable = false;
            }
        }
        return floodgateApiAvailable;
    }

    /**
     * 判断玩家是否是基岩版玩家 (通过 Floodgate 识别)。
     * 任何线程都可调用, 不抛异常。Floodgate 未加载时返回 false。
     */
    public static boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        return isBedrockPlayer(player.getUniqueId());
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;
        if (!isFloodgateApiAvailable()) return false;
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null) return false;
            return api.isFloodgatePlayer(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    // 保留旧方法名以防别的代码调用 (兼容)
    public static boolean isGeyserApiAvailable() {
        return isFloodgateApiAvailable();
    }

    // ==================== 表单发送 ====================

    /**
     * 发送 Cumulus 表单给基岩版玩家。主线程调用。
     *
     * Floodgate 直接支持 Cumulus Form 发送, 通过 FloodgatePlayer.sendForm。
     *
     * @return true 发送成功; false 玩家非基岩版 / Floodgate 未加载 / 发送异常
     */
    public static boolean sendForm(Player player, Form form) {
        if (!isBedrockPlayer(player)) return false;
        if (form == null) return false;
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null) return false;
            org.geysermc.floodgate.api.player.FloodgatePlayer fp =
                    api.getPlayer(player.getUniqueId());
            if (fp == null) return false;
            fp.sendForm(form);
            return true;
        } catch (Throwable t) {
            ProjectE.getInstance().getLogger().log(
                    Level.WARNING,
                    "Failed to send Bedrock form to " + player.getName(),
                    t
            );
            return false;
        }
    }

    // ==================== 防重放 Session Token ====================

    private static final ConcurrentHashMap<UUID, UUID> currentSession = new ConcurrentHashMap<>();

    public static UUID newSession(Player player) {
        UUID token = UUID.randomUUID();
        currentSession.put(player.getUniqueId(), token);
        return token;
    }

    public static boolean isSessionValid(Player player, UUID token) {
        if (player == null || token == null) return false;
        UUID current = currentSession.get(player.getUniqueId());
        return token.equals(current);
    }

    public static void clearSession(UUID playerUuid) {
        if (playerUuid != null) {
            currentSession.remove(playerUuid);
        }
    }

    // ==================== 显示辅助 ====================

    public static String formatEmc(long emc) {
        return String.format("%,d", emc);
    }

    public static String getDisplayName(ItemStack item) {
        if (item == null) return "";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }
}
