package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class StaffOnlineCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> onlineStaff = new ArrayList<>();

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!isStaff(target)) continue;
            if (sender instanceof Player viewer && !viewer.canSee(target)) continue;
            onlineStaff.add(formatStaffLine(target));
        }

        sender.sendMessage("§6§lStaff Online");
        if (onlineStaff.isEmpty()) {
            sender.sendMessage("§7No staff are currently online.");
            return true;
        }

        sender.sendMessage("§7Available staff: §f" + onlineStaff.size());
        for (String line : onlineStaff) {
            sender.sendMessage("§e- §f" + line);
        }
        return true;
    }

    private boolean isStaff(Player player) {
        return player.hasPermission("simplefactions.staff.helper")
                || player.hasPermission("simplefactions.staff.mod")
                || player.hasPermission("simplefactions.staff.help")
                || player.hasPermission("simplefactions.admin")
                || player.hasPermission("simplefactionsraiding.admin")
                || player.isOp();
    }

    private String formatStaffLine(Player player) {
        String role = getRoleLabel(player);
        return player.getName() + " §7(" + role + "§7)";
    }

    private String getRoleLabel(Player player) {
        if (player.hasPermission("simplefactions.admin") || player.hasPermission("simplefactionsraiding.admin") || player.isOp()) {
            return "§cAdmin";
        }
        if (player.hasPermission("simplefactions.staff.mod")) {
            return "§bMod";
        }
        if (player.hasPermission("simplefactions.staff.helper")) {
            return "§aHelper";
        }
        return "§eStaff";
    }
}