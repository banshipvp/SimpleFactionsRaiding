package local.simplefactionsraiding;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles /raid commands for raid window and core chunk management
 */
public class RaidCommand implements CommandExecutor {

    private final SimpleFactionsRaidingPlugin plugin;
    private final RaidingManager raidingManager;
    private final CoreChunkManager coreChunkManager;

    public RaidCommand(SimpleFactionsRaidingPlugin plugin, RaidingManager raidingManager, CoreChunkManager coreChunkManager) {
        this.plugin = plugin;
        this.raidingManager = raidingManager;
        this.coreChunkManager = coreChunkManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command is only for players!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "status":
                return handleStatus(player, args);
            case "info":
                return handleInfo(player, args);
            case "points":
                return handlePoints(player, args);
            case "start":
                return handleStart(player, args);
            case "stop":
                return handleStop(player, args);
            default:
                sendHelp(player);
                return true;
        }
    }

    private boolean handleStatus(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /raid status <faction>");
            return true;
        }

        String factionName = args[1];
        boolean isActive = raidingManager.isRaidActive(factionName);
        long timeRemaining = raidingManager.getTimeRemainingInWindow(factionName);

        String status = raidingManager.getRaidStatus(factionName);
        player.sendMessage("§7" + factionName + " " + status);

        long hours = timeRemaining / 3600;
        long minutes = (timeRemaining % 3600) / 60;
        player.sendMessage(String.format("§7Time remaining: §e%d§7h §e%d§7m", hours, minutes));

        return true;
    }

    private boolean handleInfo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /raid info <faction>");
            return true;
        }

        String factionName = args[1];
        CoreChunkManager.ChunkData core = coreChunkManager.getCoreChunk(factionName);

        if (core == null) {
            player.sendMessage("§c" + factionName + " has no core chunk set!");
            return true;
        }

        player.sendMessage("§a" + factionName + " Core Chunk Info:");
        player.sendMessage(String.format("§7Location: §e%s §7Chunk §e%d, %d", core.world.getName(), core.x, core.z));
        player.sendMessage(String.format("§7Current Points: §e%d", core.points));
        player.sendMessage(String.format("§7Raid Status: §a%s", raidingManager.getRaidStatus(factionName)));

        return true;
    }

    private boolean handlePoints(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /raid points <faction>");
            return true;
        }

        String factionName = args[1];
        int points = coreChunkManager.getPoints(factionName);

        player.sendMessage("§7" + factionName + " §ehas " + points + " §7core points");

        return true;
    }

    private boolean handleStart(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /raid start <faction>");
            return true;
        }

        if (!player.hasPermission("simplefactionsraiding.admin")) {
            player.sendMessage("§cYou don't have permission!");
            return true;
        }

        String factionName = args[1];
        raidingManager.setRaidMode(factionName, true);
        player.sendMessage("§a" + factionName + " raid window started!");

        return true;
    }

    private boolean handleStop(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /raid stop <faction>");
            return true;
        }

        if (!player.hasPermission("simplefactionsraiding.admin")) {
            player.sendMessage("§cYou don't have permission!");
            return true;
        }

        String factionName = args[1];
        raidingManager.setRaidMode(factionName, false);
        player.sendMessage("§a" + factionName + " raid window stopped!");

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== Raid Commands ===");
        player.sendMessage("§e/raid status <faction> §7- Check raid window status");
        player.sendMessage("§e/raid info <faction> §7- View core chunk information");
        player.sendMessage("§e/raid points <faction> §7- View faction core points");
        player.sendMessage("§e/raid start <faction> §7- (Admin) Start raid window");
        player.sendMessage("§e/raid stop <faction> §7- (Admin) Stop raid window");
    }
}
