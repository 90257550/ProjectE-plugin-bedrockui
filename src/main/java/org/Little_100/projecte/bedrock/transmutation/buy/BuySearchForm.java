package org.Little_100.projecte.bedrock.transmutation.buy;

import org.Little_100.projecte.bedrock.BedrockFormUtil;
import org.Little_100.projecte.bedrock.transmutation.TransmutationMainForm;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;

/**
 * 搜索关键字输入。
 *
 * 比 Java 版(关窗口+聊天输入)体验更好: 直接 input 组件, 不用离开 UI。
 * 搜索本身不涉及交易, 无需安全校验。
 */
public final class BuySearchForm {

    private static final int IDX_INPUT = 1;

    /** 搜索关键字最大长度, 防止构造超长字符串浪费资源 */
    private static final int MAX_KEYWORD_LENGTH = 64;

    private BuySearchForm() {}

    public static void open(Player player) {
        if (player == null || !player.isOnline()) return;

        String labelText = "§7输入物品关键字进行搜索。\n\n"
                + "§f例如: §e钻石§7, §e铁§7, §e剑§7, §e矿§7\n"
                + "§7支持中英文, 留空返回完整列表。";

        CustomForm form = CustomForm.builder()
                .title("§d§l搜索已学物品")
                .label(labelText)
                .input("关键字", "例如: diamond / 钻石", "")
                .validResultHandler(response -> {
                    if (!player.isOnline()) return;

                    String keyword = response.asInput(IDX_INPUT);
                    if (keyword == null) keyword = "";
                    keyword = keyword.trim();

                    // 长度截断
                    if (keyword.length() > MAX_KEYWORD_LENGTH) {
                        keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
                    }

                    BuyListForm.open(player, 0, keyword);
                })
                .closedOrInvalidResultHandler(() -> {
                    if (player.isOnline()) TransmutationMainForm.open(player);
                })
                .build();

        BedrockFormUtil.sendForm(player, form);
    }
}
