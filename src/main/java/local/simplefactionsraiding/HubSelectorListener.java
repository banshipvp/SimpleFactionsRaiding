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
    private final ServerStatusManager serverStatusManager;

    public HubSelectorListener(MultiWorldManager multiWorldManager, HubCommand hubCommand,
                               SimpleFactionsPlugin simpleFactionsPlugin,
                               ServerStatusManager serverStatusManager) {
        this.multiWorldManager = multiWorldManager;
        this.hubCommand = hubCommand;
        this.simpleFactionsPlugin = simpleFactionsPlugin;
        this.serverStatusManager = serverStatusManager;
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

            // Block entry when server is closed/rebooting
            if (serverStatusManager != null && serverStatusManager.isServerClosed()) {
                if (serverStatusManager.isRebooting()) {
                    player.sendMessage("§cThe Factions server is currently rebooting and is not accepting players.");
                    player.sendMessage("§7Please wait — the server will reopen once the reboot is complete.");
                } else {
                    player.sendMessage("§cThe Factions server is currently closed.");
                    player.sendMessage("§7Please check back later.");
                }
                return;
            }

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
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);

        // Fill entire GUI with aqua border panes
        ItemStack border = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName("§r");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 27; i++) inv.setItem(i, border);

        // Faction Server button centred in the middle row (slot 13)
        // Display changes based on server status
        ItemStack factionServer = new ItemStack(Material.GRASS_BLOCK, 1);
        ItemMeta meta = factionServer.getItemMeta();
        if (meta != null) {
            boolean closed = serverStatusManager != null && serverStatusManager.isServerClosed();
            boolean rebooting = serverStatusManager != null && serverStatusManager.isRebooting();
            if (rebooting) {
                meta.setDisplayName("§c§lFaction Server §7[§cREBOOTING§7]");
                meta.setLore(List.of(
                    "§cServer is currently rebooting.",
                    "§7Please check back soon — it will reopen shortly."
                ));
            } else if (closed) {
                meta.setDisplayName("§c§lFaction Server §7[§cCLOSED§7]");
                meta.setLore(List.of(
                    "§cServer is currently closed.",
                    "§7Please check back later."
                ));
            } else {
                meta.setDisplayName("§a§lFaction Server");
                meta.setLore(List.of(
                    "§7Click to join the faction spawn world",
                    "§a● §aServer is §l§aOPEN"
                ));
            }
            factionServer.setItemMeta(meta);
        }
        inv.setItem(13, factionServer);

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
