package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoRestartManager {

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;
    private BukkitTask tickerTask;
    private long secondsLeft;
    private long intervalSeconds;
    private List<Integer> warningSeconds;
    private boolean autoEnabled;
    private boolean manualRestartActive;
    private String initiatedBy = "System";

    public AutoRestartManager(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
    }

    public void start() {
        boolean enabled = plugin.getConfig().getBoolean("restart.enabled", false);
        this.autoEnabled = enabled;

        int intervalMinutes = Math.max(5, plugin.getConfig().getInt("restart.interval-minutes", 180));
        this.intervalSeconds = intervalMinutes * 60L;
        this.secondsLeft = enabled ? this.intervalSeconds : -1L;

        List<Integer> configured = plugin.getConfig().getIntegerList("restart.warning-seconds");
        if (configured == null || configured.isEmpty()) {
            configured = List.of(300, 120, 60, 30, 10, 5, 4, 3, 2, 1);
        }

        warningSeconds = new ArrayList<>(configured);
        warningSeconds.sort(Comparator.reverseOrder());

        ensureTicker();
        if (enabled) {
            plugin.getLogger().info("Auto-restart enabled every " + intervalMinutes + " minutes.");
        }
    }

    public void stop() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
    }

    private void tick() {
        if (secondsLeft < 0) {
            return;
        }

        if (secondsLeft <= 0) {
            executeRestart();
            return;
        }

        if (warningSeconds.contains((int) secondsLeft)) {
            broadcastRebootMessage((int) secondsLeft);
        }

        if (secondsLeft == 10) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                multiWorldManager.teleportToHub(player);
                player.sendMessage("§eYou were sent to hub before restart.");
            }
        }

        secondsLeft--;
    }

    private void executeRestart() {
        Bukkit.broadcastMessage("§4§lSERVER REBOOT");
        Bukkit.broadcastMessage("§cServer is rebooting now...");
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
        manualRestartActive = false;
        initiatedBy = "System";
        secondsLeft = autoEnabled ? intervalSeconds : -1L;
    }

    public long getSecondsLeft() {
        return secondsLeft;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    public void startManualRestart(int seconds, String initiator) {
        ensureTicker();
        this.manualRestartActive = true;
        this.initiatedBy = initiator == null || initiator.isBlank() ? "Admin" : initiator;

        int safeSeconds = Math.max(5, seconds);
        broadcastRebootMessage(safeSeconds);
        this.secondsLeft = safeSeconds - 1L;
    }

    public void forceRestartNow(String initiator) {
        this.manualRestartActive = true;
        this.initiatedBy = initiator == null || initiator.isBlank() ? "Admin" : initiator;
        Bukkit.broadcastMessage("§4§lSERVER REBOOT");
        Bukkit.broadcastMessage("§cServer reboot forced by §e" + initiatedBy + "§c. Restarting immediately...");
        Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
    }

    private void broadcastRebootMessage(int seconds) {
        Bukkit.broadcastMessage("§4§lSERVER REBOOT");
        Bukkit.broadcastMessage("§cServer will reboot in §e" + seconds + " seconds§c.");
        if (manualRestartActive) {
            Bukkit.broadcastMessage("§7Initiated by: §e" + initiatedBy + "§7. Please finish what you're doing and use /hub.");
        } else {
            Bukkit.broadcastMessage("§7Scheduled reboot. Please finish what you're doing and use /hub.");
        }
    }

    private void ensureTicker() {
        if (tickerTask != null) {
            return;
        }
        this.tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }
}
