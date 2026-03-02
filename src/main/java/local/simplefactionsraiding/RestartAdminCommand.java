package local.simplefactionsraiding;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class RestartAdminCommand implements CommandExecutor {

    private final AutoRestartManager autoRestartManager;

    public RestartAdminCommand(AutoRestartManager autoRestartManager) {
        this.autoRestartManager = autoRestartManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("restartnow")) {
            if (!sender.hasPermission("simplefactionsraiding.admin.restart")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }

            autoRestartManager.startManualRestart(60, sender.getName());
            sender.sendMessage("§aRestart countdown started for 60 seconds.");
            return true;
        }

        if (cmd.equals("forcerestart")) {
            if (!sender.hasPermission("simplefactionsraiding.admin.forcerestart")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }

            autoRestartManager.forceRestartNow(sender.getName());
            return true;
        }

        return false;
    }
}
