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

        if (cmd.equals("restartnow") || cmd.equals("rebootforce") || cmd.equals("forcereboot")) {
            if (!sender.hasPermission("simplefactionsraiding.admin.restart")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }

            // Start a 60-second visible countdown. At 0, all players are moved to Hub
            // and the server is closed to public access. No world reset or server restart.
            // Re-open with /serveropen when maintenance is done.
            autoRestartManager.startRebootCountdown(sender.getName());
            sender.sendMessage("§aReboot countdown started — server will close to public in §e60 seconds§a.");
            sender.sendMessage("§7Players will be moved to Hub at 0. Use §e/serveropen §7when ready to reopen.");
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
