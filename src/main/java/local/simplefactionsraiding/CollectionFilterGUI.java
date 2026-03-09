package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.Set;

/**
 * GUI system for collection chest filters.
 * Provides inventory-based menus for selecting which item categories to collect.
 */
public class CollectionFilterGUI {

    /**
     * InventoryHolder used for all collection-filter menus.
     * Storing the serialized block location lets the click listener
     * identify the menu reliably without depending on title strings.
     */
    public static class FilterMenuHolder implements InventoryHolder {
        private final String blockLocation;
        private Inventory inventory;

        public FilterMenuHolder(String blockLocation) {
            this.blockLocation = blockLocation;
        }

        public String getBlockLocation() { return blockLocation; }

        @Override
        public Inventory getInventory() { return inventory; }

        public void setInventory(Inventory inv) { this.inventory = inv; }
    }

    private final CollectionChestManager chestManager;
    private final JavaPlugin plugin;
    private final NamespacedKey filterMenuKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey subcategoryKey;
    private final NamespacedKey blockKey;

    public CollectionFilterGUI(CollectionChestManager chestManager, JavaPlugin plugin) {
        this.chestManager = chestManager;
        this.plugin = plugin;
        this.filterMenuKey = new NamespacedKey(plugin, "filter_menu");
        this.categoryKey = new NamespacedKey(plugin, "filter_category");
        this.subcategoryKey = new NamespacedKey(plugin, "filter_subcategory");
        this.blockKey = new NamespacedKey(plugin, "filter_block_location");
    }

    public void openMainFilterMenu(Player player, Block block) {
        FilterMenuHolder holder = new FilterMenuHolder(serializeLocation(block));
        Inventory inv = Bukkit.createInventory(holder, 27, "§b§lCollection Chest Filters");
        holder.setInventory(inv);
        
        CollectionChestManager.MobDropCategory[] categories = chestManager.getAllCategories();
        Set<String> enabledFilters = chestManager.getEnabledFilters(block);

        int slot = 0;
        for (CollectionChestManager.MobDropCategory category : categories) {
            if (slot >= 27) break;

            ItemStack item = getCategoryItem(category, enabledFilters.contains(category.name()));
            ItemMeta meta = item.getItemMeta();
            
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(categoryKey, PersistentDataType.STRING, category.name());
            pdc.set(blockKey, PersistentDataType.STRING, serializeLocation(block));
            item.setItemMeta(meta);

            inv.setItem(slot, item);
            slot++;
        }

        // Add close button in last slot
        ItemStack closeBtn = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeBtn.getItemMeta();
        closeMeta.setDisplayName("§c§lClose Menu");
        closeBtn.setItemMeta(closeMeta);
        inv.setItem(26, closeBtn);

        player.openInventory(inv);
    }

    public void openSubcategoryMenu(Player player, CollectionChestManager.MobDropCategory category, Block block) {
        CollectionChestManager.ItemSubcategory[] subcategories = chestManager.getSubcategoriesForCategory(category);
        FilterMenuHolder holder = new FilterMenuHolder(serializeLocation(block));
        Inventory inv = Bukkit.createInventory(holder, 9, "§b§l" + category.getDisplayName() + " Filters");
        holder.setInventory(inv);

        Set<String> enabledFilters = chestManager.getEnabledFilters(block);

        for (int i = 0; i < subcategories.length && i < 8; i++) {
            CollectionChestManager.ItemSubcategory subcat = subcategories[i];
            ItemStack item = getSubcategoryItem(category, subcat, enabledFilters);
            ItemMeta meta = item.getItemMeta();

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(categoryKey, PersistentDataType.STRING, category.name());
            pdc.set(subcategoryKey, PersistentDataType.STRING, subcat.name());
            pdc.set(blockKey, PersistentDataType.STRING, serializeLocation(block));
            item.setItemMeta(meta);

            inv.setItem(i, item);
        }

        // Back button
        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName("§e§lBack");
        backBtn.setItemMeta(backMeta);
        inv.setItem(8, backBtn);

        player.openInventory(inv);
    }

    private ItemStack getCategoryItem(CollectionChestManager.MobDropCategory category, boolean enabled) {
        // Choose display material based on category
        Material displayMat = switch (category.name()) {
            case "TNT" -> Material.TNT;
            case "GUNPOWDER" -> Material.GUNPOWDER;
            case "IRON" -> Material.IRON_INGOT;
            case "EMERALD" -> Material.EMERALD;
            case "GOLD" -> Material.GOLD_INGOT;
            case "STRING" -> Material.STRING;
            case "SPIDER_EYE" -> Material.SPIDER_EYE;
            case "FLESH" -> Material.ROTTEN_FLESH;
            case "LEATHER" -> Material.LEATHER;
            case "BONE" -> Material.BONE;
            case "BLAZE_ROD" -> Material.BLAZE_ROD;
            case "ENDER_PEARL" -> Material.ENDER_PEARL;
            case "MAGMA_CREAM" -> Material.MAGMA_CREAM;
            case "DIAMOND" -> Material.DIAMOND;
            case "COPPER" -> Material.COPPER_INGOT;
            case "QUARTZ" -> Material.QUARTZ;
            case "SLIMEBALL" -> Material.SLIME_BALL;
            case "PHANTOM_MEMBRANE" -> Material.PHANTOM_MEMBRANE;
            case "NETHERITE" -> Material.NETHERITE_INGOT;
            case "GHAST_TEAR" -> Material.GHAST_TEAR;
            case "GLOW_INK_SAC" -> Material.GLOW_INK_SAC;
            case "INK_SAC" -> Material.INK_SAC;
            case "WARDEN_DROP" -> Material.SCULK_CATALYST;
            case "EXPERIENCE" -> Material.EXPERIENCE_BOTTLE;
            default -> Material.GRAY_DYE;
        };

        ItemStack item = new ItemStack(displayMat);
        ItemMeta meta = item.getItemMeta();
        String statusColor = enabled ? "§a" : "§c";
        String status = enabled ? "Enabled" : "Disabled";
        meta.setDisplayName(statusColor + category.getDisplayName() + " - " + status);

        List<String> lore = new ArrayList<>();
        lore.add("§7Click to toggle");
        if (category.name().equals("IRON") || category.name().equals("GOLD") || 
            category.name().equals("EMERALD") || category.name().equals("DIAMOND") ||
            category.name().equals("COPPER") || category.name().equals("NETHERITE")) {
            lore.add("§e(Shift-click for subcategories)");
        }
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getSubcategoryItem(CollectionChestManager.MobDropCategory category, 
                                         CollectionChestManager.ItemSubcategory subcat, 
                                         Set<String> enabledFilters) {
        ItemStack item = new ItemStack(Material.ITEM_FRAME);
        ItemMeta meta = item.getItemMeta();
        
        String filterKey = category.name() + "_" + subcat.name();
        boolean enabled = enabledFilters.contains(filterKey);
        
        String statusColor = enabled ? "§a" : "§c";
        String status = enabled ? "Enabled" : "Disabled";
        meta.setDisplayName(statusColor + subcat.getDisplayName() + " - " + status);

        List<String> lore = new ArrayList<>();
        lore.add("§7Click to toggle");
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    public boolean isFilterMenu(org.bukkit.inventory.InventoryView view) {
        return view.getTopInventory().getHolder() instanceof FilterMenuHolder;
    }

    public String getBlockLocationFromItem(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(blockKey, PersistentDataType.STRING);
    }

    public String getCategoryFromItem(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(categoryKey, PersistentDataType.STRING);
    }

    public String getSubcategoryFromItem(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(subcategoryKey, PersistentDataType.STRING);
    }

    private String serializeLocation(Block block) {
        return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    public Block deserializeLocation(String serialized) {
        String[] parts = serialized.split(",");
        if (parts.length != 4) return null;
        try {
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return world.getBlockAt(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}
