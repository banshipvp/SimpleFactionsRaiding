package local.simplefactionsraiding;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Handles collection chest interactions including:
 * - Filter menu opening via shift-click
 * - Item collection GUI clicks
 * - Filter toggling
 */
public class CollectionChestListener implements Listener {

    private final CollectionChestManager chestManager;
    private final CollectionFilterGUI filterGUI;

    public CollectionChestListener(CollectionChestManager chestManager, CollectionFilterGUI filterGUI) {
        this.chestManager = chestManager;
        this.filterGUI = filterGUI;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (block == null || !chestManager.isCollectionChest(block)) return;

        event.setCancelled(true);

        // Left-click opens filter menu
        filterGUI.openMainFilterMenu(player, block);
        player.sendMessage("§bOpened Collection Chest filter menu");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Identify filter menus by their InventoryHolder (not by title – titles are fragile in Paper 1.21)
        if (!(event.getInventory().getHolder() instanceof CollectionFilterGUI.FilterMenuHolder holder)) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        // Get block location from the holder
        Block block = filterGUI.deserializeLocation(holder.getBlockLocation());
        if (block == null) {
            // Fall back to PDC on item meta if holder location fails
            String itemLoc = filterGUI.getBlockLocationFromItem(clicked);
            block = filterGUI.deserializeLocation(itemLoc);
            if (block == null) return;
        }

        // Close button
        if (clicked.getType().name().equals("BARRIER")) {
            player.closeInventory();
            return;
        }

        // Back button
        if (clicked.getType().name().equals("ARROW")) {
            filterGUI.openMainFilterMenu(player, block);
            return;
        }

        String category = filterGUI.getCategoryFromItem(clicked);
        String subcategory = filterGUI.getSubcategoryFromItem(clicked);

        if (category == null) return;

        CollectionChestManager.MobDropCategory mobCat = CollectionChestManager.MobDropCategory.fromName(category);
        if (mobCat == null) return;

        // If this is a subcategory item (gear/materials filters)
        if (subcategory != null) {
            handleSubcategoryClick(player, block, mobCat, subcategory, event.getClick());
        } else {
            // Main category click
            handleCategoryClick(player, block, mobCat, event.getClick());
        }
    }

    private void handleCategoryClick(Player player, Block block, CollectionChestManager.MobDropCategory category, ClickType click) {
        Set<String> enabledFilters = chestManager.getEnabledFilters(block);
        
        // Shift-click: open subcategory menu if available
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            CollectionChestManager.ItemSubcategory[] subcats = chestManager.getSubcategoriesForCategory(category);
            if (subcats.length > 1) {
                filterGUI.openSubcategoryMenu(player, category, block);
                return;
            }
        }

        // Regular click: toggle category
        if (enabledFilters.contains(category.name())) {
            chestManager.setFilterEnabled(block, category.name(), false);
            player.sendMessage("§c✗ Disabled: " + category.getDisplayName());
        } else {
            chestManager.setFilterEnabled(block, category.name(), true);
            player.sendMessage("§a✓ Enabled: " + category.getDisplayName());
        }

        // Reopen menu
        filterGUI.openMainFilterMenu(player, block);
    }

    private void handleSubcategoryClick(Player player, Block block, CollectionChestManager.MobDropCategory category,
                                       String subcategoryName, ClickType click) {
        CollectionChestManager.ItemSubcategory subcategory = CollectionChestManager.ItemSubcategory.valueOf(subcategoryName);
        Set<String> enabledFilters = chestManager.getEnabledFilters(block);
        String filterKey = category.name() + "_" + subcategory.name();

        // Toggle this subcategory
        if (enabledFilters.contains(filterKey)) {
            chestManager.setFilterEnabled(block, filterKey, false);
            player.sendMessage("§c✗ Disabled: " + category.getDisplayName() + " - " + subcategory.getDisplayName());
        } else {
            chestManager.setFilterEnabled(block, filterKey, true);
            player.sendMessage("§a✓ Enabled: " + category.getDisplayName() + " - " + subcategory.getDisplayName());
        }

        // Reopen menu
        filterGUI.openSubcategoryMenu(player, category, block);
    }

    public void handleItemDrop(Block chest, ItemStack item) {
        if (!chestManager.isCollectionChest(chest)) return;
        chestManager.addItemToChest(chest, item);
    }
}
