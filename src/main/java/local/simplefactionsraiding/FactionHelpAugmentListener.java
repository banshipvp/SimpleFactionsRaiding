package local.simplefactionsraiding;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * Appends TNT subcommands to /f help output.
 */
public class FactionHelpAugmentListener implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;

    public FactionHelpAugmentListener(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFactionHelp(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null) {
            return;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!isFactionHelpCommand(normalized)) {
            return;
        }

        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> sendTntHelpLines(player));
    }

    private boolean isFactionHelpCommand(String normalized) {
        if (normalized.equals("/f") || normalized.equals("/factions")) {
            return true;
        }

        return normalized.startsWith("/f help")
            || normalized.startsWith("/f ?")
            || normalized.startsWith("/factions help")
            || normalized.startsWith("/factions ?");
    }

    private void sendTntHelpLines(Player player) {
        player.sendMessage("§6=== Faction TNT Commands ===");
        player.sendMessage("§e/f tnt deposit (d) [amount|all]");
        player.sendMessage("§e/f tnt withdraw (w) [amount|all]");
        player.sendMessage("§e/f tnt bal (b)");
        player.sendMessage("§e/f tnt fill (f) <radius> <amountPerDispenser> <maxPerDispenser>");
        player.sendMessage("§e/f tnt siphon (s) <amountPerDispenser|all> [radius]");
    }
}
