package local.simplefactionsraiding;

import local.simplefactions.HubQueueManager;
import local.simplefactions.SimpleFactionsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class ServerCommand implements CommandExecutor {

    private final MultiWorldManager multiWorldManager;
    private final SimpleFactionsPlugin simpleFactionsPlugin;

    public ServerCommand(MultiWorldManager multiWorldManager, SimpleFactionsPlugin simpleFactionsPlugin) {
        this.multiWorldManager = multiWorldManager;
        this.simpleFactionsPlugin = simpleFactionsPlugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cUsage: /server factions");
            return true;
        }

        String target = args[0].toLowerCase(Locale.ROOT);
        if (!target.equals("factions") && !target.equals("faction") && !target.equals("f")) {
            player.sendMessage("§cUnknown server. Use: §e/server factions");
            return true;
        }

        if (tryQueue(player)) {
            return true;
        }

        boolean ok = multiWorldManager.teleportToFactionServer(player);
        if (!ok) {
            player.sendMessage("§cFaction Server world is unavailable.");
            return true;
        }

        player.sendMessage("§aConnected to §eFaction Server§a.");
        return true;
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
