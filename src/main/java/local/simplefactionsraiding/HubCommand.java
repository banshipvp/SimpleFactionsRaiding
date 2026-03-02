package local.simplefactionsraiding;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class HubCommand implements CommandExecutor {

    public static final String COMPASS_NAME = "§b§lServer Selector";

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;
    private final NamespacedKey selectorKey;

    public HubCommand(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
        this.selectorKey = new NamespacedKey(plugin, "hub_selector_compass");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        // Save last location before teleporting to hub
        multiWorldManager.saveLastLocation(player);

        if (!multiWorldManager.teleportToHub(player)) {
            player.sendMessage("§cHub world is not available.");
            return true;
        }

        giveSelectorCompass(player);
        player.sendMessage("§aSent to hub. Use your compass to choose a server.");
        return true;
    }

    public void giveSelectorCompass(Player player) {
        if (hasSelectorCompass(player)) {
            return;
        }

        ItemStack compass = new ItemStack(Material.COMPASS, 1);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(COMPASS_NAME);
            meta.setLore(List.of("§7Right-click to open server menu"));
            meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
            compass.setItemMeta(meta);
        }

        player.getInventory().addItem(compass).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    public boolean isSelectorCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(selectorKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean hasSelectorCompass(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isSelectorCompass(item)) {
                return true;
            }
        }
        return false;
    }
}
