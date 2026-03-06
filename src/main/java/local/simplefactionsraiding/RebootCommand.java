package local.simplefactionsraiding;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class RebootCommand implements CommandExecutor {

    private final AutoRestartManager autoRestartManager;

    public RebootCommand(AutoRestartManager autoRestartManager) {
        this.autoRestartManager = autoRestartManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        long seconds = autoRestartManager.getSecondsLeft();

        if (seconds < 0) {
            sender.sendMessage("§7There is no scheduled reboot at this time.");
            return true;
        }

        sender.sendMessage("§6§lNext Reboot: §r" + formatTime(seconds));
        return true;
    }

    private String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;

        if (hours > 0) {
            return String.format("§e%dh %02dm %02ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("§e%dm %02ds", minutes, secs);
        } else {
            return String.format("§c%ds", secs);
        }
    }
}
