package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages the server open/closed state and handles the 60-second pre-restart
 * countdown phase before the server actually issues the restart command.
 */
public class ServerStatusManager implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;

    private boolean serverClosed = false;

    /** True while the server is in dev-maintenance/reboot mode (post-countdown, waiting for /serveropen). */
    private boolean rebooting = false;

    /** True while counting down the 60-second pre-restart window. */
    private boolean inPreRestart = false;
    private int preRestartSecondsLeft = 0;
    private BukkitTask preRestartTask = null;

    public ServerStatusManager(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Login gate ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!serverClosed) return;
        if (isStaff(event.getPlayer())) return;

        String msg;
        if (inPreRestart && preRestartSecondsLeft > 0) {
            msg = "§4Server Reboot\n§cServer is restarting. Try again in §e"
                    + preRestartSecondsLeft + " §cseconds.";
        } else if (rebooting) {
            msg = "§4Server Rebooting\n§cThe Factions server is currently rebooting.\n§7Please check back soon — the server will reopen shortly.";
        } else {
            msg = "§4Server Closed\n§cThe server is temporarily closed to the public.";
        }
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, msg);
    }

    // ── Pre-restart phase ─────────────────────────────────────────────────────

    /**
     * Closes the server to public, kicks all non-staff players with a countdown
     * message, then waits {@code seconds} seconds before calling {@code onComplete}
     * (which triggers the actual Bukkit restart command).
     */
    public void startPreRestartPhase(int seconds, Runnable onComplete) {
        serverClosed = true;
        inPreRestart = true;
        preRestartSecondsLeft = Math.max(1, seconds);

        // Kick non-staff immediately
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isStaff(p)) {
                p.kickPlayer("§4Server Reboot\n§cServer is restarting.\n§7Back online in approximately §e"
                        + preRestartSecondsLeft + " §7seconds.");
            }
        }

        Bukkit.broadcastMessage("§4§lSERVER REBOOT §7— Restarting in §e" + preRestartSecondsLeft + " §7seconds...");

        if (preRestartTask != null) {
            preRestartTask.cancel();
        }

        preRestartTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            preRestartSecondsLeft--;

            // Broadcast visible timer to any remaining staff
            if (preRestartSecondsLeft > 0 && (preRestartSecondsLeft <= 10 || preRestartSecondsLeft % 15 == 0)) {
                Bukkit.broadcastMessage("§c§lRestarting in §e" + preRestartSecondsLeft + "§c§l seconds...");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle("§c§lRESTARTING", "§7Back online in §e" + preRestartSecondsLeft + "s", 0, 25, 5);
                }
            }

            if (preRestartSecondsLeft <= 0) {
                preRestartTask.cancel();
                preRestartTask = null;
                inPreRestart = false;
                onComplete.run();
            }
        }, 20L, 20L);
    }

    // ── Manual open/close ─────────────────────────────────────────────────────

    public void openServer() {
        serverClosed = false;
        rebooting = false;
        inPreRestart = false;
        preRestartSecondsLeft = 0;
        if (preRestartTask != null) {
            preRestartTask.cancel();
            preRestartTask = null;
        }
    }

    public void closeServer() {
        serverClosed = true;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isServerClosed() {
        return serverClosed;
    }

    public boolean isRebooting() {
        return rebooting;
    }

    public void setRebooting(boolean rebooting) {
        this.rebooting = rebooting;
    }

    public int getPreRestartSecondsLeft() {
        return preRestartSecondsLeft;
    }

    public boolean isInPreRestart() {
        return inPreRestart;
    }

    // ── Staff check ───────────────────────────────────────────────────────────

    public static boolean isStaff(Player player) {
        return player.hasPermission("group.owner")
                || player.hasPermission("group.admin")
                || player.hasPermission("group.dev")
                || player.hasPermission("simplefactionsraiding.admin.bypass");
    }
}
