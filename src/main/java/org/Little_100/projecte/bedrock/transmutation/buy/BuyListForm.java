package org.Little_100.projecte.bedrock.transmutation.buy;

import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.Little_100.projecte.compatibility.scheduler.SchedulerAdapter;
import org.Little_100.projecte.managers.EmcManager;
import org.Little_100.projecte.managers.SearchLanguageManager;
import org.Little_100.projecte.storage.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 购买物品列表 (分页, 带异步加载)。
 *
 * 唯一使用异步的 Form:
 * 1. 异步线程读 learned_items + 玩家 EMC
 * 2. 完成后切回主线程构建 Form 并发送
 *
 * 防刷说明:
 * - 这只是一个展示列表, 真正的 EMC 扣除在 BuyConfirmForm + EmcManager.buyItem
 * - 所以这里不需要 session 校验
 * - 但点击物品进入下一级时, 会为下一级生成 token
 */
public final class BuyListForm {

    private static final int PAGE_SIZE = 20;

    private BuyListForm() {}

    public static void open(Player player, int page, String filter) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        SchedulerAdapter scheduler = ProjectE.getInstance().getSchedulerAdapter();

        scheduler.runTaskAsynchronously(() -> {
            DatabaseManager db = ProjectE.getInstance().getDatabaseManager();
            List<String> learnedKeys = db.getLearnedItems(uuid);
            long playerEmc = db.getPlayerEmc(uuid);

            scheduler.runTask(() -> {
                if (player.isOnline()) {
                    buildAndSend(player, learnedKeys, playerEmc, page, filter);
                }
            });
        });
    }

    private static void buildAndSend(Player player, List<String> learnedKeys,
                                     long playerEmc, int page, String filter) {
        EmcManager emcManager = ProjectE.getInstance().getEmcManager();
        SearchLanguageManager searchLang = ProjectE.getInstance().getSearchLanguageManager();

        List<String> filtered = filterKeys(learnedKeys, filter, searchLang, emcManager);

        if (filtered.isEmpty()) {
            handleEmpty(player, filter);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, filtered.size());

        String title;
        if (filter == null || filter.isEmpty()) {
            title = "§b§l购买物品 §7(" + (page + 1) + "/" + totalPages + ")";
        } else {
            title = "§b§l搜索: " + filter + " §7(" + (page + 1) + "/" + totalPages + ")";
        }

        String content = "§f你的 EMC: §e" + BedrockFormUtil.formatEmc(playerEmc) + "\n"
                + "§f已学习: §e" + learnedKeys.size() + " §f种\n"
                + "§7点击物品选择数量购买:";

        SimpleForm.Builder builder = SimpleForm.builder().title(title).content(content);

        // 第 0 个按钮: 搜索
        builder.button("§e§l🔍 搜索物品");

        List<String> pageKeys = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String key = filtered.get(i);
            long unitEmc = emcManager.getEmc(key);
            ItemStack sample = ProjectE.getInstance().getItemStackFromKey(key);
            String name = sample != null ? BedrockFormUtil.getDisplayName(sample) : key;

            builder.button("§f" + name + "\n§7" + BedrockFormUtil.formatEmc(unitEmc) + " EMC/个");
            pageKeys.add(key);
        }

        boolean hasPrev = page > 0;
        boolean hasNext = page < totalPages - 1;
        if (hasPrev) builder.button("§a« 上一页");
        if (hasNext) builder.button("§a下一页 »");
        builder.button("§7返回主菜单");

        final int currentPage = page;
        final String currentFilter = filter;
        final boolean finalHasPrev = hasPrev;
        final boolean finalHasNext = hasNext;
        final int itemCount = pageKeys.size();

        builder.validResultHandler(response -> {
            if (!player.isOnline()) return;

            int id = response.clickedButtonId();

            if (id == 0) {
                BuySearchForm.open(player);
                return;
            }
            int idx = id - 1;

            if (idx < itemCount) {
                String chosenKey = pageKeys.get(idx);
                BuyChoiceForm.open(player, chosenKey, currentPage, currentFilter);
                return;
            }
            idx -= itemCount;

            if (finalHasPrev && idx == 0) {
                open(player, currentPage - 1, currentFilter);
                return;
            }
            if (finalHasPrev) idx--;

            if (finalHasNext && idx == 0) {
                open(player, currentPage + 1, currentFilter);
                return;
            }
            TransmutationMainForm.open(player);
        });

        BedrockFormUtil.sendForm(player, builder.build());
    }

    private static void handleEmpty(Player player, String filter) {
        String content;
        if (filter == null || filter.isEmpty()) {
            content = "§7你还没有学习任何物品。\n§7先通过出售物品解锁它们吧!";
        } else {
            content = "§7没有找到匹配 '§e" + filter + "§7' 的已学物品。";
        }

        ModalForm empty = ModalForm.builder()
                .title("§e无结果")
                .content(content)
                .button1("§a返回主菜单")
                .button2("§7重新搜索")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;
                    if (response.clickedButtonId() == 0) TransmutationMainForm.open(player);
                    else BuySearchForm.open(player);
                })
                .build();
        BedrockFormUtil.sendForm(player, empty);
    }

    /** 过滤已学习物品 key 列表, 复用 Java 版搜索逻辑 */
    private static List<String> filterKeys(List<String> allKeys, String filter,
                                           SearchLanguageManager searchLang,
                                           EmcManager emcManager) {
        List<String> withEmc = new ArrayList<>();
        for (String key : allKeys) {
            if (emcManager.getEmc(key) > 0) withEmc.add(key);
        }

        if (filter == null || filter.isEmpty()) return withEmc;

        String searchLower = filter.toLowerCase();
        Map<String, String> matchingIds = searchLang.findMatchingIds(searchLower);

        List<String> result = new ArrayList<>();
        if (!matchingIds.isEmpty()) {
            for (String key : withEmc) {
                ItemStack sample = ProjectE.getInstance().getItemStackFromKey(key);
                if (sample == null) continue;

                String typeLower = sample.getType().name().toLowerCase();
                String minecraftId = "item.minecraft." + typeLower;
                String blockId = "block.minecraft." + typeLower;
                String displayName = sample.getItemMeta() != null
                        && sample.getItemMeta().hasDisplayName()
                        ? sample.getItemMeta().getDisplayName().toLowerCase()
                        : typeLower;

                if (matchingIds.containsKey(minecraftId) || matchingIds.containsKey(blockId)
                        || displayName.contains(searchLower)
                        || typeLower.contains(searchLower)
                        || typeLower.replace("_", " ").contains(searchLower)) {
                    result.add(key);
                }
            }
        } else {
            for (String key : withEmc) {
                ItemStack sample = ProjectE.getInstance().getItemStackFromKey(key);
                if (sample == null) continue;

                String displayName = sample.getItemMeta() != null
                        && sample.getItemMeta().hasDisplayName()
                        ? sample.getItemMeta().getDisplayName().toLowerCase() : "";
                String typeName = sample.getType().name().toLowerCase();

                if (displayName.contains(searchLower) || typeName.contains(searchLower)
                        || typeName.replace("_", " ").contains(searchLower)) {
                    result.add(key);
                }
            }
        }
        return result;
    }
}
