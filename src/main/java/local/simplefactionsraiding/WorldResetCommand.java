package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Locale;

public class WorldResetCommand implements CommandExecutor {

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;

    public WorldResetCommand(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.worldreset")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /worldreset <spawn|nether|end|worldname> confirm");
            return true;
        }

        String targetArg = args[0].toLowerCase(Locale.ROOT);
        String confirm = args[1].toLowerCase(Locale.ROOT);
        if (!"confirm".equals(confirm)) {
            sender.sendMessage("§cAdd §econfirm §cto execute reset.");
            return true;
        }

        String worldName = resolveWorldName(targetArg);
        if (worldName == null) {
            sender.sendMessage("§cUnknown world target: §f" + targetArg);
            return true;
        }

        if (worldName.equalsIgnoreCase(multiWorldManager.getHubWorldName())) {
            sender.sendMessage("§cResetting hub while online is blocked. Reset spawn/nether/end worlds only.");
            return true;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not loaded: §f" + worldName);
            return true;
        }

        World hub = multiWorldManager.getHubWorld();
        if (hub == null) {
            sender.sendMessage("§cHub world unavailable, cannot safely move players.");
            return true;
        }

        for (Player player : world.getPlayers()) {
            player.teleport(hub.getSpawnLocation().clone().add(0.5, 0.1, 0.5));
            player.sendMessage("§eWorld reset in progress. You were moved to hub.");
        }

        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (!unloaded) {
            sender.sendMessage("§cFailed to unload world: §f" + worldName);
            return true;
        }

        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (worldFolder.exists() && !deleteRecursive(worldFolder)) {
            sender.sendMessage("§cFailed to delete world folder: §f" + worldFolder.getAbsolutePath());
            return true;
        }

        World.Environment environment = resolveEnvironment(worldName);
        World recreated = new WorldCreator(worldName)
                .environment(environment)
                .type(WorldType.NORMAL)
                .generateStructures(true)
                .createWorld();

        if (recreated == null) {
            sender.sendMessage("§cFailed to recreate world: §f" + worldName);
            return true;
        }

        sender.sendMessage("§aWorld reset complete: §e" + worldName);
        return true;
    }

    private String resolveWorldName(String targetArg) {
        return switch (targetArg) {
            case "spawn", "faction", "factions" -> multiWorldManager.getFactionWorldName();
            case "nether" -> multiWorldManager.getFactionNetherWorldName();
            case "end" -> multiWorldManager.getFactionEndWorldName();
            default -> targetArg;
        };
    }

    private World.Environment resolveEnvironment(String worldName) {
        if (worldName.equalsIgnoreCase(multiWorldManager.getFactionNetherWorldName())) {
            return World.Environment.NETHER;
        }
        if (worldName.equalsIgnoreCase(multiWorldManager.getFactionEndWorldName())) {
            return World.Environment.THE_END;
        }
        return World.Environment.NORMAL;
    }

    private boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                if (!deleteRecursive(child)) {
                    return false;
                }
            }
        }

        return file.delete();
    }
}
