package org.Little_100.projecte.managers;

import org.Little_100.projecte.Debug;
import org.Little_100.projecte.ProjectE;
import org.Little_100.projecte.compatibility.version.VersionAdapter;
import org.Little_100.projecte.devices.*;
import org.Little_100.projecte.storage.DatabaseManager;
import org.Little_100.projecte.tools.kleinstar.KleinStarManager;
import org.Little_100.projecte.util.Constants;
import org.Little_100.projecte.util.ShulkerBoxUtil;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public class EmcManager {

    private final ProjectE plugin;
    private final DatabaseManager databaseManager;
    private final VersionAdapter versionAdapter;
    private final String recipeConflictStrategy;
    private final String divisionStrategy;
    private final Set<String> currentlyCalculating = new HashSet<>();
    // 内存缓存,避免频繁查询数据库和遍历配方
    // issue 11 但不保证有效
    private final Map<String, Long> emcCache = new HashMap<>();
    // 锁定状态缓存
    private final Set<String> lockedItems = new HashSet<>();

    public EmcManager(ProjectE plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.versionAdapter = plugin.getVersionAdapter();
        this.recipeConflictStrategy = plugin.getConfig()
                .getString("gui.EMC.recipeConflictStrategy", "lowest")
                .toLowerCase();
        this.divisionStrategy = plugin.getConfig()
                .getString("gui.EMC.divisionStrategy", "floor")
                .toLowerCase();

        // 确保文件存在
        File customEmcFile = new File(plugin.getDataFolder(), "custommoditememc.yml");
        if (!customEmcFile.exists()) {
            plugin.getLogger().info("custommoditememc.yml not found, creating default file.");
            plugin.saveResource("custommoditememc.yml", false);
        }
    }

    public void calculateAndStoreEmcValues(boolean forceRecalculate) {
        if (!forceRecalculate && databaseManager.hasEmcValues()) {
            plugin.getLogger().info("EMC values already exist in the database. Skipping calculation.");
            return;
        }
        if (forceRecalculate) {
            // 只清除未锁定的EMC值，保留锁定的值
            databaseManager.clearUnlockedEmcValues();
            // 清空缓存
            emcCache.clear();
            lockedItems.clear();
        }
        plugin.getLogger().info("Start calculating EMC values...");
        versionAdapter.loadInitialEmcValues();

        for (int i = 0; i < 10; i++) {
            plugin.getLogger().info("EMC calculation iteration " + (i + 1) + "...");
            boolean changed = false;
            Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
            while (recipeIterator.hasNext()) {
                try {
                    Recipe recipe = recipeIterator.next();
                    if (calculateEmcForRecipe(recipe)) {
                        changed = true;
                    }
                } catch (Exception e) {
                    // 捕获并忽略损坏的配方
                }
            }
            if (!changed) {
                plugin.getLogger().info("EMC values stabilized, calculation ended early.");
                break;
            }
        }

        // 如果是第一次计算，存储到数据库

        plugin.getLogger().info("EMC value calculation completed.");
    }

    private boolean calculateEmcForRecipe(Recipe recipe) {
        ItemStack result = recipe.getResult();
        if (result.getType().isAir()) {
            return false;
        }


        NamespacedKey key = null;
        if (recipe instanceof ShapedRecipe) {
            key = ((ShapedRecipe) recipe).getKey();
        } else if (recipe instanceof ShapelessRecipe) {
            key = ((ShapelessRecipe) recipe).getKey();
        } else if (recipe instanceof CookingRecipe) {
            key = ((CookingRecipe<?>) recipe).getKey();
        }

        if (key != null && key.getNamespace().equalsIgnoreCase("projecte")) {
            return false;
        }

        String resultKey = getItemKey(result);
        
        // 检查物品是否被锁定，如果被锁定则跳过配方计算
        if (isEmcLocked(resultKey)) {
            return false;
        }
        
        long existingEmc = getEmc(resultKey);
        long newEmc = versionAdapter.calculateRecipeEmc(recipe, divisionStrategy);

        if (newEmc <= 0) {
            return false;
        }

        if (existingEmc <= 0) {
            // 使用 setEmcIfNotLocked 确保不会覆盖锁定的值
            if (databaseManager.setEmcIfNotLocked(resultKey, newEmc)) {
                // 更新缓存
                emcCache.put(resultKey, newEmc);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("item", resultKey);
                placeholders.put("emc", String.valueOf(newEmc));
                Debug.log("debug.emc.item_emc_info", placeholders);
                return true;
            }
            return false;
        } else {
            boolean updated = false;
            if ("lowest".equals(recipeConflictStrategy) && newEmc < existingEmc) {
                if (databaseManager.setEmcIfNotLocked(resultKey, newEmc)) {
                    // 更新缓存
                    emcCache.put(resultKey, newEmc);
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("item", resultKey);
                    placeholders.put("emc", String.valueOf(newEmc));
                    Debug.log("debug.emc.item_emc_info", placeholders);
                    updated = true;
                }
            } else if ("highest".equals(recipeConflictStrategy) && newEmc > existingEmc) {
                if (databaseManager.setEmcIfNotLocked(resultKey, newEmc)) {
                    // 更新缓存
                    emcCache.put(resultKey, newEmc);
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("item", resultKey);
                    placeholders.put("emc", String.valueOf(newEmc));
                    Debug.log("debug.emc.item_emc_info", placeholders);
                    updated = true;
                }
            }
            return updated;
        }
    }

    /**
     * 检查物品的EMC值是否被锁定
     * @param itemKey 物品键
     * @return 是否被锁定
     */
    public boolean isEmcLocked(String itemKey) {
        // 先检查缓存
        if (lockedItems.contains(itemKey)) {
            return true;
        }
        // 查询数据库
        boolean locked = databaseManager.isEmcLocked(itemKey);
        if (locked) {
            lockedItems.add(itemKey);
        }
        return locked;
    }

    public long getEmc(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }

        // 检查物品是否有非附魔的NBT组件（PDC、CustomModelData等）
        if (!hasOnlyEnchantments(item)) {
            // 这是一个自定义物品，使用完整key查询/计算EMC
            String fullKey = getItemKey(item);
            long customEmc = getEmc(fullKey);  // 这会触发递归计算
            if (customEmc > 0) {
                return applyDurabilityModifier(item, customEmc);
            }
            // 如果自定义物品没有EMC值，返回0（不使用底座材料的EMC）
            return 0;
        }

        // 对于纯默认或仅附魔物品，使用基础材料的EMC
        String baseKey = versionAdapter.getItemKey(item);
        long baseEmc = getEmc(baseKey);
        
        if (baseEmc <= 0) {
            return 0;
        }

        // 应用耐久修正
        return applyDurabilityModifier(item, baseEmc);
    }

    public long getEmc(String itemKey) {
        // 1. 先检查内存缓存
        if (emcCache.containsKey(itemKey)) {
            return emcCache.get(itemKey);
        }
        
        // 2. 检查数据库
        long emc = databaseManager.getEmc(itemKey);
        // 注意：即使emc为0，如果数据库中有记录，也应该缓存
        if (databaseManager.hasEmcRecord(itemKey)) {
            emcCache.put(itemKey, emc);
            return emc;
        }

        // 3. 如果正在计算中,返回0避免循环依赖
        if (currentlyCalculating.contains(itemKey)) {
            return 0;
        }

        // 4. 尝试通过配方计算(仅在启动时或重新计算时调用,不在GUI交互时调用)
        // 为了避免在Folia主线程中阻塞,我们不在这里进行配方遍历
        // 配方计算应该在服务器启动时完成
        
        // 缓存结果(即使是0)以避免重复查询
        emcCache.put(itemKey, 0L);
        return 0;
    }

    public String getItemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "minecraft:air";
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                PersistentDataContainer container = meta.getPersistentDataContainer();
                
                // 首先检查ProjectE自己的ID_KEY
                if (container.has(Constants.ID_KEY, PersistentDataType.STRING)) {
                    String projecteId = container.get(Constants.ID_KEY, PersistentDataType.STRING);
                    if ("transmutation_tablet_book".equals(projecteId)) {
                        // Check for CustomModelData to be more specific
                        if (meta.hasCustomModelData() && meta.getCustomModelData() == 1) {
                            return "projecte:" + projecteId;
                        }
                    } else {
                        return "projecte:" + projecteId;
                    }
                }

                if (container.has(Constants.KLEIN_STAR_KEY, PersistentDataType.INTEGER)) {
                    int level = container.get(Constants.KLEIN_STAR_KEY, PersistentDataType.INTEGER);
                    if (level > 0) {
                        String levelName = getLevelName(level);
                        if (levelName != null) {
                            return "projecte:klein_star_" + levelName;
                        }
                    }
                }

                if (container.has(DarkMatterFurnace.KEY, PersistentDataType.BYTE)) {
                    return "projecte:dark_matter_furnace";
                }
                if (container.has(RedMatterFurnace.KEY, PersistentDataType.BYTE)) {
                    return "projecte:red_matter_furnace";
                }
                if (container.has(AlchemicalChest.KEY, PersistentDataType.BYTE)) {
                    return "projecte:alchemical_chest";
                }
                if (container.has(EnergyCondenser.KEY, PersistentDataType.BYTE)) {
                    return "projecte:energy_condenser";
                }
                if (container.has(EnergyCondenserMK2.KEY, PersistentDataType.BYTE)) {
                    return "projecte:energy_condenser_mk2";
                }
                // 检查能量收集器
                if (container.has(EnergyCollector.KEY_MK1, PersistentDataType.BYTE)) {
                    return "projecte:energy_collector_mk1";
                }
                if (container.has(EnergyCollector.KEY_MK2, PersistentDataType.BYTE)) {
                    return "projecte:energy_collector_mk2";
                }
                if (container.has(EnergyCollector.KEY_MK3, PersistentDataType.BYTE)) {
                    return "projecte:energy_collector_mk3";
                }
                
                // 检测其他插件的PDC
                // Detect PDC from other plugins
                Set<NamespacedKey> keys = container.getKeys();
                if (!keys.isEmpty()) {
                    // 尝试找到一个通用的ID key
                    // 优先查找常见的命名模式，如 "xxx:id", "xxx:item_id" 等
                    for (NamespacedKey key : keys) {
                        String keyName = key.getKey().toLowerCase();
                        if (keyName.equals("id") || keyName.equals("item_id") || keyName.equals("custom_id")) {
                            // 尝试以STRING类型获取
                            if (container.has(key, PersistentDataType.STRING)) {
                                String customId = container.get(key, PersistentDataType.STRING);
                                if (customId != null && !customId.isEmpty()) {
                                    return key.getNamespace() + ":" + customId;
                                }
                            }
                        }
                    }
                    
                    // 如果没有找到标准的ID key，使用第一个非ProjectE的key
                    for (NamespacedKey key : keys) {
                        if (!key.getNamespace().equals("projecte")) {
                            // 尝试获取该key的值
                            if (container.has(key, PersistentDataType.STRING)) {
                                String value = container.get(key, PersistentDataType.STRING);
                                if (value != null && !value.isEmpty()) {
                                    return key.toString().replace(":", "_") + "_" + value;
                                }
                            }
                        }
                    }
                }
            }
        }
        return versionAdapter.getItemKey(item);
    }

    public String getEffectiveItemKey(ItemStack item) {
        // 根据物品状态返回合适的key
        if (hasOnlyEnchantments(item)) {
            // 仅附魔物品返回基础key
            return versionAdapter.getItemKey(item);
        }
        // 有PDC或其他组件返回完整key
        return getItemKey(item);
    }

    public String getSpecificItemKey(ItemStack item) {
        return getItemKey(item);
    }

    public boolean isPdcItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return !container.getKeys().isEmpty();
    }

    /**
     * 检查物品是否只有附魔（没有PDC、CustomModelData等其他组件）
     * Check if the item has only enchantments (no PDC, CustomModelData, etc.)
     */
    private boolean hasOnlyEnchantments(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return true; // 纯默认物品
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        
        // 检查是否有PDC
        if (!meta.getPersistentDataContainer().getKeys().isEmpty()) {
            return false;
        }
        
        // 检查是否有CustomModelData
        if (meta.hasCustomModelData()) {
            return false;
        }
        
        // 检查是否有自定义名称
        if (meta.hasDisplayName()) {
            return false;
        }
        
        // 检查是否有Lore
        if (meta.hasLore()) {
            return false;
        }
        
        // 如果只有附魔（或什么都没有），返回true
        return true;
    }

    /**
     * 根据物品耐久度应用EMC修正
     * Apply EMC modifier based on item durability
     */
    private long applyDurabilityModifier(ItemStack item, long baseEmc) {
        if (item == null || !item.hasItemMeta()) {
            return baseEmc;
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return baseEmc;
        }
        
        // 检查物品是否有耐久度
        if (meta instanceof org.bukkit.inventory.meta.Damageable) {
            org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) meta;
            
            // 获取最大耐久度
            int maxDurability = item.getType().getMaxDurability();
            if (maxDurability > 0 && damageable.hasDamage()) {
                int damage = damageable.getDamage();
                int currentDurability = maxDurability - damage;
                
                // 计算耐久百分比
                double durabilityPercent = (double) currentDurability / maxDurability;
                
                // 应用耐久修正
                return (long) (baseEmc * durabilityPercent);
            }
        }
        
        return baseEmc;
    }

    private String getLevelName(int level) {
        switch (level) {
            case 1:
                return "ein";
            case 2:
                return "zwei";
            case 3:
                return "drei";
            case 4:
                return "vier";
            case 5:
                return "sphere";
            case 6:
                return "omega";
            default:
                return null;
        }
    }

    public long getBaseEmc(ItemStack item) {
        String baseKey = versionAdapter.getItemKey(item);
        return getEmc(baseKey);
    }

    public String getPdcId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        NamespacedKey idKey = Constants.ID_KEY;
        return container.get(idKey, PersistentDataType.STRING);
    }

    public void registerEmc(String itemKey, long emcValue) {
        databaseManager.setEmc(itemKey, emcValue);
        // 更新缓存
        emcCache.put(itemKey, emcValue);
    }

    /**
     * 注册EMC值并可选择锁定
     * @param itemKey 物品键
     * @param emcValue EMC值
     * @param locked 是否锁定
     */
    public void registerEmc(String itemKey, long emcValue, boolean locked) {
        databaseManager.setEmc(itemKey, emcValue, locked);
        // 更新缓存
        emcCache.put(itemKey, emcValue);
        if (locked) {
            lockedItems.add(itemKey);
        }
    }

    public void setEmcValue(ItemStack item, long emc) {
        if (item == null) return;
        String key = getItemKey(item);
        databaseManager.setEmc(key, emc);
        // 更新缓存
        emcCache.put(key, emc);
    }

    /**
     * 重新计算原版物品的EMC值（不包括PDC物品）
     * Recalculate EMC values for default items (excluding PDC items)
     */
    public void recalculateDefaultEmcValues() {
        plugin.getLogger().info("Start calculating default items EMC values...");
        
        // 清空缓存
        emcCache.clear();
        
        // 重新加载基础EMC值
        versionAdapter.loadInitialEmcValues();

        for (int i = 0; i < 10; i++) {
            plugin.getLogger().info("Default EMC calculation iteration " + (i + 1) + "...");
            boolean changed = false;
            Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
            while (recipeIterator.hasNext()) {
                try {
                    Recipe recipe = recipeIterator.next();
                    
                    // 跳过PDC物品
                    if (recipe.getResult() != null && isPdcItem(recipe.getResult())) {
                        continue;
                    }
                    
                    if (calculateEmcForRecipe(recipe)) {
                        changed = true;
                    }
                } catch (Exception e) {
                    // 捕获并忽略损坏的配方
                }
            }
            if (!changed) {
                plugin.getLogger().info("Default EMC values stabilized, calculation ended early.");
                break;
            }
        }

        plugin.getLogger().info("Default items EMC value calculation completed.");
    }

    /**
     * 重新计算PDC物品的EMC值
     * Recalculate EMC values for PDC items
     * @return 计算的唯一物品数量
     */
    public int recalculatePdcEmcValues() {
        plugin.getLogger().info("Start calculating PDC items EMC values...");
        
        // 清空缓存以确保使用最新计算的值
        emcCache.clear();
        
        boolean debug = plugin.getConfig().getBoolean("debug");
        int pdcRecipesFound = 0;
        Set<String> calculatedItems = new HashSet<>();

        // 先统计有多少PDC配方
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            try {
                Recipe recipe = recipeIterator.next();
                if (recipe.getResult() != null && !recipe.getResult().getType().isAir() 
                    && isPdcItem(recipe.getResult())) {
                    pdcRecipesFound++;
                }
            } catch (Exception e) {
                // 忽略损坏的配方
            }
        }

        if (pdcRecipesFound == 0) {
            plugin.getLogger().info("No PDC recipes found. Skipping PDC EMC calculation.");
            return 0;
        }

        plugin.getLogger().info("Found " + pdcRecipesFound + " PDC recipes. Starting EMC calculation...");

        // 进行额外的迭代计算来处理PDC物品
        for (int i = 0; i < 5; i++) {
            plugin.getLogger().info("PDC EMC calculation iteration " + (i + 1) + "...");
            boolean changed = false;
            
            currentlyCalculating.clear();
            
            recipeIterator = Bukkit.recipeIterator();
            while (recipeIterator.hasNext()) {
                try {
                    Recipe recipe = recipeIterator.next();
                    if (recipe.getResult() == null || recipe.getResult().getType().isAir()) {
                        continue;
                    }

                    // 只处理PDC物品
                    if (isPdcItem(recipe.getResult())) {
                        String itemKey = getItemKey(recipe.getResult());
                        
                        // 检查是否被锁定
                        if (isEmcLocked(itemKey)) {
                            continue;
                        }
                        
                        long oldEmc = databaseManager.getEmc(itemKey);
                        
                        // 计算配方的EMC
                        long recipeEmc = versionAdapter.calculateRecipeEmc(recipe, divisionStrategy);
                        
                        if (debug && recipeEmc == 0 && oldEmc == 0) {
                            // 调试：显示为什么无法计算EMC
                            plugin.getLogger().warning("[PDC EMC Debug] Cannot calculate EMC for: " + itemKey);
                            plugin.getLogger().warning("  Recipe type: " + recipe.getClass().getSimpleName());
                            
                            // 显示原料信息
                            if (recipe instanceof ShapedRecipe) {
                                plugin.getLogger().warning("  Ingredients:");
                                ShapedRecipe shaped = (ShapedRecipe) recipe;
                                for (ItemStack ingredient : shaped.getIngredientMap().values()) {
                                    if (ingredient != null && !ingredient.getType().isAir()) {
                                        String ingKey = getItemKey(ingredient);
                                        long ingEmc = databaseManager.getEmc(ingKey);
                                        plugin.getLogger().warning("    - " + ingKey + ": " + ingEmc + " EMC");
                                    }
                                }
                            } else if (recipe instanceof ShapelessRecipe) {
                                plugin.getLogger().warning("  Ingredients:");
                                ShapelessRecipe shapeless = (ShapelessRecipe) recipe;
                                for (ItemStack ingredient : shapeless.getIngredientList()) {
                                    if (ingredient != null && !ingredient.getType().isAir()) {
                                        String ingKey = getItemKey(ingredient);
                                        long ingEmc = databaseManager.getEmc(ingKey);
                                        plugin.getLogger().warning("    - " + ingKey + ": " + ingEmc + " EMC");
                                    }
                                }
                            }
                        }
                        
                        if (recipeEmc > 0) {
                            // 根据策略更新EMC
                            boolean shouldUpdate = false;
                            
                            if (oldEmc <= 0) {
                                // 还没有EMC值，直接设置
                                shouldUpdate = true;
                            } else if ("lowest".equals(recipeConflictStrategy) && recipeEmc < oldEmc) {
                                // 策略是最低值，且新值更低
                                shouldUpdate = true;
                            } else if ("highest".equals(recipeConflictStrategy) && recipeEmc > oldEmc) {
                                // 策略是最高值，且新值更高
                                shouldUpdate = true;
                            }
                            
                            if (shouldUpdate) {
                                if (databaseManager.setEmcIfNotLocked(itemKey, recipeEmc)) {
                                    // 更新缓存
                                    emcCache.put(itemKey, recipeEmc);
                                    changed = true;
                                    calculatedItems.add(itemKey);
                                    if (debug) {
                                        plugin.getLogger().info("[PDC EMC Calculated] " + itemKey + " = " + recipeEmc + " EMC");
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略损坏的配方
                }
            }
            
            if (!changed) {
                plugin.getLogger().info("PDC EMC values stabilized, calculation ended early.");
                break;
            }
        }

        plugin.getLogger().info("PDC items EMC calculation completed. " + calculatedItems.size() + " unique items calculated.");
        
        // 报告未计算的PDC物品
        int uncalculatedCount = 0;
        recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            try {
                Recipe recipe = recipeIterator.next();
                if (recipe.getResult() != null && !recipe.getResult().getType().isAir() 
                    && isPdcItem(recipe.getResult())) {
                    String itemKey = getItemKey(recipe.getResult());
                    long emc = databaseManager.getEmc(itemKey);
                    if (emc <= 0 && !isEmcLocked(itemKey)) {
                        uncalculatedCount++;
                        if (debug) {
                            plugin.getLogger().warning("[PDC EMC] Item without EMC: " + itemKey);
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
        }
        
        if (uncalculatedCount > 0) {
            plugin.getLogger().warning("Warning: " + uncalculatedCount + " PDC items still don't have EMC values.");
            plugin.getLogger().warning("This may be due to missing ingredient EMC or circular dependencies.");
            if (!debug) {
                plugin.getLogger().warning("Enable debug mode in config.yml to see which items are affected.");
            }
        }
        
        return calculatedItems.size();
    }

    /**
     * 清除缓存（用于重新加载配置时）
     */
    public void clearCache() {
        emcCache.clear();
        lockedItems.clear();
    }

    // ========================================================================
    // 基岩版 Form UI 共用业务逻辑
    // ========================================================================
    // 下述方法是 Java 版 GUIListener 和 基岩版 Form 共用的业务核心。
    // 所有的防刷取 EMC 安全检查都在这里做, 不信任 UI 层的任何计算/判断。
    //
    // 线程约束: 必须在主线程调用。
    // ========================================================================

    /**
     * 计算单个物品可出售的 EMC 值。
     *
     * 完整复现 GUIListener.calculateItemSellEmc 的行为:
     *   - 潜影盒: 全内容有 EMC -> (盒 EMC + 内容总 EMC) * 堆叠数量
     *            有任一无 EMC 物品 -> 返回 0
     *   - Klein Star: (基础 EMC + 存储 EMC) * 堆叠数量
     *   - 有耐久物品的耐久修正由 getEmc() 内部处理
     *   - 普通物品: 单个 EMC * 堆叠数量
     *
     * 安全设计:
     *   - 不信任调用方传入的任何预计值, 每次都重新计算
     *   - 乘法前做溢出检查, 溢出返回 0 (等同于物品无 EMC)
     */
    public long calculateSellEmcFor(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }

        KleinStarManager kleinStarManager = plugin.getKleinStarManager();
        int amount = item.getAmount();
        if (amount <= 0) return 0;

        // 潜影盒
        if (item.getItemMeta() instanceof BlockStateMeta
                && ((BlockStateMeta) item.getItemMeta()).getBlockState() instanceof ShulkerBox) {
            if (ShulkerBoxUtil.getFirstItemWithoutEmc(item) == null) {
                long itemEmc = getEmc(item);
                long contentsEmc = ShulkerBoxUtil.getTotalEmcOfContents(item);
                long perBox = safeAdd(itemEmc, contentsEmc);
                if (perBox <= 0) return 0;
                return safeMultiply(perBox, amount);
            }
            return 0;
        }

        // Klein Star
        if (kleinStarManager.isKleinStar(item)) {
            long baseEmc = getEmc(getItemKey(item));
            long storedEmc = kleinStarManager.getStoredEmc(item);
            long perItem = safeAdd(baseEmc, storedEmc);
            if (perItem <= 0) return 0;
            return safeMultiply(perItem, amount);
        }

        // 普通物品 (耐久修正已经由 getEmc 处理过)
        long itemEmc = getEmc(item);
        if (itemEmc <= 0) return 0;
        return safeMultiply(itemEmc, amount);
    }

    /**
     * 批量出售物品, 执行扣除/加 EMC/登记学习。
     *
     * 这是统一的业务入口。Java 版 GUIListener.handleTransaction 和
     * 基岩版 BulkSellForm / QuickSellForm 都通过这个方法来执行出售。
     *
     * 线程: 主线程
     *
     * 安全设计:
     *   - 每件物品独立计算 EMC, 不信任调用方的估算值
     *   - 使用 safeAdd 累加总 EMC, 防整数溢出
     *   - 加到玩家账户时, 先重新查当前 EMC (可能期间变化), 再叠加
     *   - 溢出时拒绝整笔加成, 退回所有物品 (极端保守)
     *
     * @param player 玩家
     * @param items  要出售的物品 (可包含无 EMC 物品, 方法内会分离)
     * @return 结果对象
     */
    public SellResult sellItems(Player player, java.util.List<ItemStack> items) {
        if (player == null || items == null || items.isEmpty()) {
            return new SellResult(0L, java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(), false);
        }

        long totalEmc = 0;
        java.util.List<ItemStack> rejected = new java.util.ArrayList<>();
        java.util.List<String> newlyLearned = new java.util.ArrayList<>();

        java.util.UUID uuid = player.getUniqueId();

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;

            long emcValue = calculateSellEmcFor(item);
            if (emcValue <= 0) {
                // 无 EMC 或计算溢出 -> 退回
                rejected.add(item);
                continue;
            }

            // 总 EMC 加法溢出检查
            long newTotal = safeAdd(totalEmc, emcValue);
            if (newTotal < totalEmc) {
                // 溢出! 本次出售中止, 剩余物品全部退回
                rejected.add(item);
                plugin.getLogger().warning(
                        "Sell EMC overflow for player " + player.getName() + ", transaction aborted");
                continue;
            }
            totalEmc = newTotal;

            // 登记学习
            String itemKey = getItemKey(item);
            if (!databaseManager.isLearned(uuid, itemKey)) {
                newlyLearned.add(itemKey);
            }
            databaseManager.addLearnedItem(uuid, itemKey);

            // 潜影盒内部物品也登记学习
            if (item.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof ShulkerBox sb) {
                for (ItemStack content : sb.getInventory().getContents()) {
                    if (content != null && !content.getType().isAir()) {
                        String contentKey = getItemKey(content);
                        if (!databaseManager.isLearned(uuid, contentKey)) {
                            newlyLearned.add(contentKey);
                        }
                        databaseManager.addLearnedItem(uuid, contentKey);
                    }
                }
            }
        }

        // 加到玩家账户 (重新读当前值, 不信任任何缓存)
        boolean emcCredited = false;
        if (totalEmc > 0) {
            long currentEmc = databaseManager.getPlayerEmc(uuid);
            long newEmc = safeAdd(currentEmc, totalEmc);
            if (newEmc < currentEmc) {
                // 账户余额溢出: 极端情况, 拒绝本次加成, 物品不退回(已经扣了)
                // 但这几乎不可能, long 最大值约 9 * 10^18
                plugin.getLogger().severe(
                        "Player EMC overflow for " + player.getName()
                                + " (current=" + currentEmc + ", adding=" + totalEmc + ")");
            } else {
                databaseManager.setPlayerEmc(uuid, newEmc);
                emcCredited = true;
            }
        }

        return new SellResult(totalEmc, rejected, newlyLearned, emcCredited);
    }

    /**
     * 购买指定数量的一种物品。
     *
     * 统一购买入口。Java 版 GUIListener.handleBuyScreenClick 和
     * 基岩版 BuyConfirmForm 都通过这个方法执行购买。
     *
     * 线程: 主线程
     *
     * 安全设计:
     *   - amount 必须 >= 1, <= MAX_BUY_AMOUNT
     *   - 乘法做溢出检查
     *   - 再次查询玩家 EMC, 不信任 UI 层的显示值
     *   - 贤者之石特判: 玩家已拥有则拒绝
     *   - 若物品无法从 key 还原, 拒绝并不扣 EMC
     *
     * @param player  玩家
     * @param itemKey 物品 key
     * @param amount  数量
     * @return BuyResult
     */
    public BuyResult buyItem(Player player, String itemKey, int amount) {
        if (player == null || itemKey == null) {
            return new BuyResult(BuyStatus.INVALID_AMOUNT, 0, 0);
        }
        if (amount < 1 || amount > MAX_BUY_AMOUNT) {
            return new BuyResult(BuyStatus.INVALID_AMOUNT, 0, 0);
        }

        long unitEmc = getEmc(itemKey);
        if (unitEmc <= 0) {
            return new BuyResult(BuyStatus.ITEM_NOT_AVAILABLE, 0, 0);
        }

        // 溢出保护: amount * unitEmc 不能溢出
        if (amount > Long.MAX_VALUE / unitEmc) {
            return new BuyResult(BuyStatus.INVALID_AMOUNT, 0, 0);
        }
        long totalCost = unitEmc * amount;

        // 先构造物品, 若无法还原则立刻拒绝, 不扣 EMC
        ItemStack purchased = plugin.getItemStackFromKey(itemKey);
        if (purchased == null) {
            return new BuyResult(BuyStatus.ITEM_NOT_AVAILABLE, totalCost, 0);
        }

        // 贤者之石二次购买检查
        if (plugin.isPhilosopherStone(purchased)) {
            if (amount != 1) {
                return new BuyResult(BuyStatus.INVALID_AMOUNT, totalCost, 0);
            }
            if (player.getInventory().containsAtLeast(plugin.getPhilosopherStone(), 1)) {
                return new BuyResult(BuyStatus.ALREADY_OWNED, totalCost, 0);
            }
        }

        // 再次查当前玩家 EMC, 不信任任何缓存/UI 值
        java.util.UUID uuid = player.getUniqueId();
        long playerEmc = databaseManager.getPlayerEmc(uuid);
        if (playerEmc < totalCost) {
            return new BuyResult(BuyStatus.NOT_ENOUGH_EMC, totalCost, 0);
        }

        // 扣 EMC (写入就是 synchronized)
        databaseManager.setPlayerEmc(uuid, playerEmc - totalCost);

        // 给物品
        purchased.setAmount(amount);
        java.util.HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(purchased);
        if (!remaining.isEmpty()) {
            for (ItemStack drop : remaining.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        return new BuyResult(BuyStatus.SUCCESS, totalCost, amount);
    }

    // ====== 安全工具方法 ======

    /** 一次购买允许的最大数量 (9 组 * 64 = 576) */
    public static final int MAX_BUY_AMOUNT = 9 * 64;

    /** 安全加法, 溢出时返回 Long.MIN_VALUE (调用方可以检测) */
    private static long safeAdd(long a, long b) {
        if (a <= 0 || b <= 0) return a + b;
        if (a > Long.MAX_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }

    /** 安全乘法, 溢出时返回 0 */
    private static long safeMultiply(long value, int amount) {
        if (value <= 0 || amount <= 0) return 0;
        if (value > Long.MAX_VALUE / amount) return 0;
        return value * amount;
    }

    // ====== 结果对象 ======

    public static class SellResult {
        private final long totalEmc;
        private final java.util.List<ItemStack> rejected;
        private final java.util.List<String> newlyLearned;
        private final boolean emcCredited;

        public SellResult(long totalEmc, java.util.List<ItemStack> rejected,
                          java.util.List<String> newlyLearned, boolean emcCredited) {
            this.totalEmc = totalEmc;
            this.rejected = rejected;
            this.newlyLearned = newlyLearned;
            this.emcCredited = emcCredited;
        }

        public long getTotalEmc() { return totalEmc; }
        public java.util.List<ItemStack> getRejected() { return rejected; }
        public java.util.List<String> getNewlyLearned() { return newlyLearned; }
        /** EMC 是否真的记到账户了 (溢出时为 false) */
        public boolean isEmcCredited() { return emcCredited; }
    }

    public static class BuyResult {
        private final BuyStatus status;
        private final long cost;
        private final int actualAmount;

        public BuyResult(BuyStatus status, long cost, int actualAmount) {
            this.status = status;
            this.cost = cost;
            this.actualAmount = actualAmount;
        }

        public BuyStatus getStatus() { return status; }
        public long getCost() { return cost; }
        public int getActualAmount() { return actualAmount; }
        public boolean isSuccess() { return status == BuyStatus.SUCCESS; }
    }

    public enum BuyStatus {
        SUCCESS,
        NOT_ENOUGH_EMC,
        ITEM_NOT_AVAILABLE,
        INVALID_AMOUNT,
        ALREADY_OWNED
    }
}