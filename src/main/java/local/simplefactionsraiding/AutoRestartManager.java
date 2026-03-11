package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

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
    private boolean shutdownOnExecute = true;
    private ServerStatusManager serverStatusManager = null;

    /** How long to hold the server closed before issuing the Bukkit restart command. */
    private static final int PRE_RESTART_SECONDS = 60;

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
            secondsLeft = -1; // prevent repeated triggering
            executeRestart();
            return;
        }

        if (warningSeconds.contains((int) secondsLeft)) {
            broadcastRebootMessage((int) secondsLeft);
        }

        secondsLeft--;
    }

    private void executeRestart() {
        // Teleport all players to hub immediately when restart fires
        Bukkit.broadcastMessage("§e§lSERVER REBOOT §7— Sending all players to Hub now!");
        for (Player player : Bukkit.getOnlinePlayers()) {
            multiWorldManager.teleportToHub(player);
            player.sendMessage("§eYou have been moved to Hub. The Factions server is restarting.");
        }

        if (shutdownOnExecute) {
            // Enter pre-restart phase: close server to public, show 60-second countdown, THEN restart.
            if (serverStatusManager != null) {
                serverStatusManager.startPreRestartPhase(PRE_RESTART_SECONDS, this::doActualRestart);
            } else {
                doActualRestart();
            }
        } else {
            // Live reboot — players stay connected
            for (Player player : Bukkit.getOnlinePlayers()) {
                multiWorldManager.teleportToHub(player);
                player.sendMessage("§aLive reboot complete. You remain connected in hub.");
            }
            Bukkit.broadcastMessage("§aLive reboot complete. Server stayed online.");
            manualRestartActive = false;
            initiatedBy = "System";
            shutdownOnExecute = true;
            secondsLeft = autoEnabled ? intervalSeconds : -1L;
        }
    }

    /** Issues the actual Bukkit restart/shutdown command. Called after the pre-restart phase. */
    private void doActualRestart() {
        Bukkit.broadcastMessage("§4§lSERVER REBOOT §7— Restarting now!");
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.getServer().savePlayers();
                Bukkit.getWorlds().forEach(world -> {
                    try { world.save(); } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}

            ConsoleCommandSender console = Bukkit.getConsoleSender();
            boolean restartIssued = false;
            try {
                restartIssued = Bukkit.dispatchCommand(console, "restart");
            } catch (Throwable ignored) {}

            if (!restartIssued) {
                plugin.getLogger().warning("Restart command was unavailable; falling back to shutdown.");
                Bukkit.shutdown();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    Bukkit.shutdown();
                }
            }, 100L);
        });
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

    public void setServerStatusManager(ServerStatusManager manager) {
        this.serverStatusManager = manager;
    }

    /**
     * Moves all online players to Hub, then resets the faction worlds WITHOUT
     * stopping the server process. Players remain connected in Hub during the
     * reset (≈30 seconds) and can re-enter Factions once the new world is ready.
     * Used by /rebootforce and /forcereboot.
     */
    public void closeServerForReboot(String initiator) {
        this.manualRestartActive = true;
        this.initiatedBy = initiator == null || initiator.isBlank() ? "Admin" : initiator;

        // Move every player to Hub immediately — they stay connected the whole time.
        Bukkit.broadcastMessage("§4§lMAP RESET §7— Initiated by §e" + this.initiatedBy);
        Bukkit.broadcastMessage("§6The faction worlds are being wiped and regenerated. Stay in Hub!");
        for (Player player : Bukkit.getOnlinePlayers()) {
            multiWorldManager.teleportToHub(player);
            player.sendMessage("§eThe faction map is resetting. You have been moved to Hub — stay here!");
        }

        // Block new logins while the worlds are being reset.
        if (serverStatusManager != null) {
            serverStatusManager.closeServer();
        }

        // Short countdown so players can see what's happening, then reset worlds.
        scheduleWorldReboot(30);
    }

    // ── World-reboot helpers (no server process restart) ────────────────────

    /**
     * Counts down {@code seconds} seconds with chat + title broadcasts, then
     * calls {@link #doWorldReboot()}. Players are NOT kicked — they wait in Hub.
     */
    private void scheduleWorldReboot(int seconds) {
        final int[] remaining = {Math.max(5, seconds)};
        Bukkit.broadcastMessage("§6§lMAP RESET §7— World wipe begins in §e" + remaining[0] + " §7seconds. Stay in Hub!");

        new BukkitRunnable() {
            @Override
            public void run() {
                remaining[0]--;
                if (remaining[0] > 0) {
                    if (remaining[0] <= 10 || remaining[0] % 10 == 0) {
                        Bukkit.broadcastMessage("§6§lMAP RESET §c— Starting in §e" + remaining[0] + "§c seconds...");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            p.sendTitle("§6§lMAP RESET", "§7Starting in §e" + remaining[0] + "s", 0, 25, 5);
                        }
                    }
                } else {
                    cancel();
                    doWorldReboot();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** Resets all three faction worlds, then reopens the server. No process restart. */
    private void doWorldReboot() {
        Bukkit.broadcastMessage("§6§lMAP RESET §7— Wiping and regenerating faction worlds, please wait...");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§6§lMAP RESET", "§7Regenerating worlds...", 0, 120, 10);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            resetWorld(multiWorldManager.getFactionWorldName(), World.Environment.NORMAL);
            resetWorld(multiWorldManager.getFactionNetherWorldName(), World.Environment.NETHER);
            resetWorld(multiWorldManager.getFactionEndWorldName(), World.Environment.THE_END);

            // Reset internal state
            manualRestartActive = false;
            initiatedBy = "System";
            shutdownOnExecute = true;
            secondsLeft = autoEnabled ? intervalSeconds : -1L;

            // Reopen server to new logins
            if (serverStatusManager != null) {
                serverStatusManager.openServer();
            }

            Bukkit.broadcastMessage("§a§lMAP RESET COMPLETE §7— The faction worlds have been regenerated!");
            Bukkit.broadcastMessage("§aFactions has reset. Use §e/spawn §aor §e/factions §ato enter the new world.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§a§lMAP RESET COMPLETE", "§7Enter the new world with §e/spawn", 10, 80, 20);
            }
        });
    }

    /** Evacuates remaining players from {@code worldName}, unloads it, deletes its folder, then recreates it. */
    private void resetWorld(String worldName, World.Environment environment) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            // Safety-net: move any lingering players (e.g. staff) to hub
            for (Player p : world.getPlayers()) {
                multiWorldManager.teleportToHub(p);
            }
            Bukkit.unloadWorld(world, false); // false = do NOT save old chunks
        }

        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (worldFolder.exists()) {
            deleteRecursive(worldFolder);
        }

        World created = new WorldCreator(worldName)
                .environment(environment)
                .type(WorldType.NORMAL)
                .generateStructures(true)
                .createWorld();

        if (created == null) {
            plugin.getLogger().severe("[MapReset] Failed to recreate world: " + worldName);
        } else {
            plugin.getLogger().info("[MapReset] Reset complete: " + worldName);
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    // ── Standard restart helpers ──────────────────────────────────────────────

    public void startManualRestart(int seconds, String initiator) {
        startManualRestart(seconds, initiator, true);
    }

    public void startManualRestart(int seconds, String initiator, boolean shutdownServer) {
        ensureTicker();
        this.manualRestartActive = true;
        this.initiatedBy = initiator == null || initiator.isBlank() ? "Admin" : initiator;
        this.shutdownOnExecute = shutdownServer;

        int safeSeconds = Math.max(5, seconds);
        broadcastRebootMessage(safeSeconds);
        this.secondsLeft = safeSeconds - 1L;
    }

    public void forceRestartNow(String initiator) {
        this.manualRestartActive = true;
        this.initiatedBy = initiator == null || initiator.isBlank() ? "Admin" : initiator;
        this.shutdownOnExecute = true;
        Bukkit.broadcastMessage("§4§lSERVER REBOOT");
        Bukkit.broadcastMessage("§cServer reboot forced by §e" + initiatedBy + "§c. Restarting immediately...");
        Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
    }

    public void startLiveReboot(int seconds, String initiator) {
        startManualRestart(seconds, initiator, false);
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
