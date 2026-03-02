package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class HubSelectorListener implements Listener {

    private static final String MENU_TITLE = "§8Server Selector";

    private final MultiWorldManager multiWorldManager;
    private final HubCommand hubCommand;

    public HubSelectorListener(MultiWorldManager multiWorldManager, HubCommand hubCommand) {
        this.multiWorldManager = multiWorldManager;
        this.hubCommand = hubCommand;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!hubCommand.isSelectorCompass(item)) {
            return;
        }

        event.setCancelled(true);
        openSelector(event.getPlayer());
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!MENU_TITLE.equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (clicked.getType() == Material.GRASS_BLOCK) {
            player.closeInventory();
            boolean ok = multiWorldManager.teleportToFactionServer(player);
            if (ok) {
                player.sendMessage("§aConnected to §eFaction Server§a.");
            } else {
                player.sendMessage("§cFaction Server world is unavailable.");
            }
        }
    }

    private void openSelector(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, MENU_TITLE);

        ItemStack factionServer = new ItemStack(Material.GRASS_BLOCK, 1);
        ItemMeta meta = factionServer.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a§lFaction Server");
            meta.setLore(List.of("§7Click to join the faction spawn world"));
            factionServer.setItemMeta(meta);
        }

        inv.setItem(4, factionServer);
        player.openInventory(inv);
    }
}
