package local.simplefactionsraiding;

import local.simplefactions.HubQueueManager;
import local.simplefactions.SimpleFactionsPlugin;
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
    private final SimpleFactionsPlugin simpleFactionsPlugin;

    public HubSelectorListener(MultiWorldManager multiWorldManager, HubCommand hubCommand,
                               SimpleFactionsPlugin simpleFactionsPlugin) {
        this.multiWorldManager = multiWorldManager;
        this.hubCommand = hubCommand;
        this.simpleFactionsPlugin = simpleFactionsPlugin;
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
            if (tryQueue(player)) {
                return;
            }
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

    private boolean tryQueue(Player player) {
        if (simpleFactionsPlugin == null) return false;
        HubQueueManager queue = simpleFactionsPlugin.getHubQueueManager();
        if (queue == null) return false;

        boolean added = queue.enqueue(player);
        if (added) {
            int pos = queue.getPosition(player.getUniqueId());
            int size = queue.size();
            player.sendMessage("§a✔ You joined the §6Factions §aqueue!");
            player.sendMessage("§e  Position: §f" + pos + "§e/§f" + size);
        } else {
            int pos = queue.getPosition(player.getUniqueId());
            int size = queue.size();
            player.sendMessage("§eYou are already in the queue at position §f" + pos + "§e/§f" + size + "§e.");
        }
        return true;
    }
}
