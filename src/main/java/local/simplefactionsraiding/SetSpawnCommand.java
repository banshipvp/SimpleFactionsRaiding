package local.simplefactionsraiding;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetSpawnCommand implements CommandExecutor {

    private final SimpleFactionsRaidingPlugin plugin;

    public SetSpawnCommand(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (!player.hasPermission("simplefactionsraiding.setspawn")) {
            player.sendMessage("§cYou don't have permission to set spawn.");
            return true;
        }

        Location loc = player.getLocation();
        String worldName = loc.getWorld().getName();
        String path = "custom-spawns." + worldName;
        
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", loc.getYaw());
        plugin.getConfig().set(path + ".pitch", loc.getPitch());
        plugin.saveConfig();

        player.sendMessage("§aSpawn location set for world: §e" + worldName);
        return true;
    }
}
