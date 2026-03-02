package local.simplefactionsraiding;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class ServerCommand implements CommandExecutor {

    private final MultiWorldManager multiWorldManager;

    public ServerCommand(MultiWorldManager multiWorldManager) {
        this.multiWorldManager = multiWorldManager;
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

        boolean ok = multiWorldManager.teleportToFactionServer(player);
        if (!ok) {
            player.sendMessage("§cFaction Server world is unavailable.");
            return true;
        }

        player.sendMessage("§aConnected to §eFaction Server§a.");
        return true;
    }
}
