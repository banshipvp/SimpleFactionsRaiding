package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ServerOpenCloseCommand implements CommandExecutor {

    private final ServerStatusManager serverStatusManager;

    public ServerOpenCloseCommand(ServerStatusManager serverStatusManager) {
        this.serverStatusManager = serverStatusManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.restart")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("serveropen")) {
            serverStatusManager.openServer();
            Bukkit.broadcastMessage("§a§lSERVER OPEN §7— The server is now open to all players.");
            sender.sendMessage("§aServer successfully opened to the public.");

        } else if (cmd.equals("serverclose")) {
            serverStatusManager.closeServer();
            Bukkit.broadcastMessage("§c§lSERVER CLOSED §7— The server has been closed to the public by §e" + sender.getName() + "§7.");
            sender.sendMessage("§cServer closed. Public players cannot join. Use §e/serveropen §cto re-open.");
        }

        return true;
    }
}
