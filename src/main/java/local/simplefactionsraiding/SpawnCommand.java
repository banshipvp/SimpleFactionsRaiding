package local.simplefactionsraiding;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnCommand implements CommandExecutor {

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;

    public SpawnCommand(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        Location spawn = getSpawnLocation(player);
        if (spawn == null) {
            player.sendMessage("§cSpawn location not set for this world.");
            return true;
        }

        player.teleport(spawn);
        player.sendMessage("§aTeleported to spawn.");
        return true;
    }

    private Location getSpawnLocation(Player player) {
        String worldName = player.getWorld().getName();
        
        // Check if custom spawn is set
        String path = "custom-spawns." + worldName;
        if (plugin.getConfig().contains(path)) {
            double x = plugin.getConfig().getDouble(path + ".x");
            double y = plugin.getConfig().getDouble(path + ".y");
            double z = plugin.getConfig().getDouble(path + ".z");
            float yaw = (float) plugin.getConfig().getDouble(path + ".yaw");
            float pitch = (float) plugin.getConfig().getDouble(path + ".pitch");
            return new Location(player.getWorld(), x, y, z, yaw, pitch);
        }
        
        // Fallback to world spawn
        return player.getWorld().getSpawnLocation();
    }
}
