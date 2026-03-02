package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Manages collection chests data, filters, and item storage.
 * Collection chests can automatically collect items from mobs and terrain.
 * Filters can be set per chunk to control what items are collected.
 */
public class CollectionChestManager {

    private final JavaPlugin plugin;
    private final NamespacedKey collectionChestKey;
    private final NamespacedKey trappedChestKey;
    private final NamespacedKey chestFilterKey;
    private final Map<String, Set<String>> chunkFilters = new HashMap<>(); // chunk_key -> enabled filters

    public enum MobDropCategory {
        TNT("TNT", new Material[]{Material.TNT}),
        GUNPOWDER("Gunpowder", new Material[]{Material.GUNPOWDER}),
        IRON("Iron", new Material[]{Material.IRON_INGOT, Material.IRON_BLOCK, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON, Material.RAW_IRON_BLOCK}),
        EMERALD("Emerald", new Material[]{Material.EMERALD, Material.EMERALD_BLOCK, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE}),
        GOLD("Gold", new Material[]{Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.RAW_GOLD, Material.RAW_GOLD_BLOCK}),
        STRING("String", new Material[]{Material.STRING}),
        SPIDER_EYE("Spider Eye", new Material[]{Material.SPIDER_EYE}),
        FLESH("Flesh", new Material[]{Material.ROTTEN_FLESH}),
        LEATHER("Leather", new Material[]{Material.LEATHER}),
        BONE("Bone", new Material[]{Material.BONE}),
        BLAZE_ROD("Blaze Rod", new Material[]{Material.BLAZE_ROD}),
        ENDER_PEARL("Ender Pearl", new Material[]{Material.ENDER_PEARL}),
        MAGMA_CREAM("Magma Cream", new Material[]{Material.MAGMA_CREAM}),
        DIAMOND("Diamond", new Material[]{Material.DIAMOND, Material.DIAMOND_BLOCK, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE}),
        COPPER("Copper", new Material[]{Material.COPPER_INGOT, Material.COPPER_BLOCK, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.RAW_COPPER, Material.RAW_COPPER_BLOCK}),
        QUARTZ("Quartz", new Material[]{Material.QUARTZ, Material.QUARTZ_BLOCK}),
        SLIMEBALL("Slimeball", new Material[]{Material.SLIME_BALL}),
        PHANTOM_MEMBRANE("Phantom Membrane", new Material[]{Material.PHANTOM_MEMBRANE}),
        NETHERITE("Netherite", new Material[]{Material.NETHERITE_INGOT, Material.NETHERITE_SCRAP, Material.ANCIENT_DEBRIS}),
        GHAST_TEAR("Ghast Tear", new Material[]{Material.GHAST_TEAR}),
        GLOW_INK_SAC("Glow Ink Sac", new Material[]{Material.GLOW_INK_SAC}),
        INK_SAC("Ink Sac", new Material[]{Material.INK_SAC}),
        WARDEN_DROP("Warden Drop", new Material[]{Material.SCULK_CATALYST}),
        EXPERIENCE("Experience", new Material[]{Material.EXPERIENCE_BOTTLE});

        final String displayName;
        final Material[] materials;

        MobDropCategory(String displayName, Material[] materials) {
            this.displayName = displayName;
            this.materials = materials;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Material[] getMaterials() {
            return materials;
        }

        public static MobDropCategory fromName(String name) {
            try {
                return MobDropCategory.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public enum ItemSubcategory {
        ALL("All"),
        BLOCKS_ONLY("Blocks Only"),
        INGOTS_ONLY("Ingots Only"),
        ORES_ONLY("Ores Only"),
        RAW_ONLY("Raw Only");

        final String displayName;

        ItemSubcategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public CollectionChestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.collectionChestKey = new NamespacedKey(plugin, "collection_chest");
        this.trappedChestKey = new NamespacedKey(plugin, "trapped_collection_chest");
        this.chestFilterKey = new NamespacedKey(plugin, "collection_chest_filter");
    }

    public boolean isCollectionChest(Block block) {
        if (block == null || !(block.getState() instanceof Container)) return false;
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return false;
        
        Container container = (Container) block.getState();
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        return pdc.has(collectionChestKey, PersistentDataType.BYTE) || pdc.has(trappedChestKey, PersistentDataType.BYTE);
    }

    public void createCollectionChest(Block block, boolean trapped) {
        if (!(block.getState() instanceof Container container)) return;
        
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        if (trapped) {
            pdc.set(trappedChestKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.set(collectionChestKey, PersistentDataType.BYTE, (byte) 1);
        }
        
        container.update();
        initializeChunkFilters(block.getChunk().getChunkKey());
    }

    public boolean isTrappedChest(Block block) {
        if (block == null || !(block.getState() instanceof Container container)) return false;
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        return pdc.has(trappedChestKey, PersistentDataType.BYTE);
    }

    public void initializeChunkFilters(long chunkKey) {
        String key = chunkKey + "";
        if (!chunkFilters.containsKey(key)) {
            Set<String> defaultFilters = new HashSet<>();
            // Enable all categories by default
            for (MobDropCategory cat : MobDropCategory.values()) {
                defaultFilters.add(cat.name());
            }
            chunkFilters.put(key, defaultFilters);
        }
    }

    public Set<String> getEnabledFilters(Block block) {
        String chunkKey = block.getChunk().getChunkKey() + "";
        return chunkFilters.getOrDefault(chunkKey, new HashSet<>());
    }

    public void setFilterEnabled(Block block, String filterName, boolean enabled) {
        String chunkKey = block.getChunk().getChunkKey() + "";
        initializeChunkFilters(Long.parseLong(chunkKey));
        Set<String> filters = chunkFilters.get(chunkKey);
        if (enabled) {
            filters.add(filterName);
        } else {
            filters.remove(filterName);
        }
    }

    public boolean canCollect(Block chest, ItemStack item) {
        if (item == null || item.getAmount() == 0) return false;

        Material material = item.getType();
        Set<String> enabledFilters = getEnabledFilters(chest);

        // Check each enabled filter category
        for (String filterName : enabledFilters) {
            MobDropCategory category = MobDropCategory.fromName(filterName);
            if (category != null) {
                for (Material mat : category.getMaterials()) {
                    if (mat == material) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public boolean addItemToChest(Block block, ItemStack item) {
        if (!isCollectionChest(block) || !canCollect(block, item)) return false;

        if (!(block.getState() instanceof Container container)) return false;
        Inventory inv = container.getInventory();

        ItemStack toAdd = item.clone();
        HashMap<Integer, ItemStack> leftover = inv.addItem(toAdd);

        return leftover.isEmpty();
    }

    public Material[] getFilteredMaterials(MobDropCategory category, ItemSubcategory subcategory) {
        if (subcategory == ItemSubcategory.ALL) {
            return category.getMaterials();
        }

        List<Material> filtered = new ArrayList<>();
        for (Material mat : category.getMaterials()) {
            String matName = mat.name();
            switch (subcategory) {
                case BLOCKS_ONLY -> {
                    if (matName.contains("BLOCK")) filtered.add(mat);
                }
                case INGOTS_ONLY -> {
                    if (matName.contains("INGOT") && !matName.contains("BLOCK")) filtered.add(mat);
                }
                case ORES_ONLY -> {
                    if (matName.contains("ORE")) filtered.add(mat);
                }
                case RAW_ONLY -> {
                    if (matName.contains("RAW")) filtered.add(mat);
                }
            }
        }

        return filtered.toArray(new Material[0]);
    }

    public MobDropCategory[] getAllCategories() {
        return MobDropCategory.values();
    }

    public ItemSubcategory[] getSubcategoriesForCategory(MobDropCategory category) {
        String catName = category.name();
        if (catName.equals("IRON") || catName.equals("GOLD") || catName.equals("EMERALD") || 
            catName.equals("DIAMOND") || catName.equals("COPPER") || catName.equals("NETHERITE")) {
            return new ItemSubcategory[]{ItemSubcategory.ALL, ItemSubcategory.BLOCKS_ONLY, ItemSubcategory.INGOTS_ONLY};
        }
        return new ItemSubcategory[]{ItemSubcategory.ALL};
    }
}
