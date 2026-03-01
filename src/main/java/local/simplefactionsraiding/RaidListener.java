package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles TNT explosions, core chunk raiding, and raid notifications
 */
public class RaidListener implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;
    private final RaidingManager raidingManager;
    private final CustomTNTManager customTNTManager;
    private final CoreChunkManager coreChunkManager;
    private final ArrayDeque<RaidImpact> impactQueue = new ArrayDeque<>();
    private final Map<String, AggregatedRaidMessage> pendingBroadcasts = new HashMap<>();

    private final int maxImpactsPerTick;
    private final int maxQueueSize;
    private final long broadcastIntervalTicks;

    private long ticksSinceLastBroadcast = 0;
    private int droppedQueueEvents = 0;

    public RaidListener(SimpleFactionsRaidingPlugin plugin, RaidingManager raidingManager,
                        CustomTNTManager customTNTManager, CoreChunkManager coreChunkManager) {
        this.plugin = plugin;
        this.raidingManager = raidingManager;
        this.customTNTManager = customTNTManager;
        this.coreChunkManager = coreChunkManager;

        this.maxImpactsPerTick = Math.max(1, plugin.getConfig().getInt("performance.max-core-impacts-per-tick", 300));
        this.maxQueueSize = Math.max(1000, plugin.getConfig().getInt("performance.max-core-impact-queue-size", 100000));
        this.broadcastIntervalTicks = Math.max(1, plugin.getConfig().getLong("performance.broadcast-interval-ticks", 20));

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainImpactQueue, 1L, 1L);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed)) {
            return;
        }

        TNTPrimed tnt = (TNTPrimed) event.getEntity();
        CustomTNTManager.CustomTNTType type = customTNTManager.getCustomTNTType(tnt);

        Location explodeLocation = event.getLocation();
        int blockX = explodeLocation.getBlockX();
        int blockY = explodeLocation.getBlockY();
        int blockZ = explodeLocation.getBlockZ();
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        CoreChunkManager.ChunkData coreChunk = coreChunkManager.getCoreChunkAt(chunkX, chunkZ, explodeLocation.getWorld());
        if (coreChunk == null) {
            return;
        }

        event.setCancelled(true);
        String defendingFaction = coreChunk.factionName;
        String attackerName = tnt.getSource() instanceof Player ? ((Player) tnt.getSource()).getName() : "Someone";

        if (!raidingManager.isRaidActive(defendingFaction)) {
            explodeLocation.getWorld().playSound(explodeLocation, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            enqueueImpact(new RaidImpact(defendingFaction, attackerName, type, 0, blockY, true));
            return;
        }

        int pointsToSteal = raidingManager.calculatePointsStolen(100, blockY);
        enqueueImpact(new RaidImpact(defendingFaction, attackerName, type, pointsToSteal, blockY, false));
    }

    private void enqueueImpact(RaidImpact impact) {
        if (impactQueue.size() >= maxQueueSize) {
            droppedQueueEvents++;
            return;
        }
        impactQueue.offer(impact);
    }

    private void drainImpactQueue() {
        int processed = 0;
        while (processed < maxImpactsPerTick && !impactQueue.isEmpty()) {
            RaidImpact impact = impactQueue.poll();
            if (impact == null) {
                break;
            }

            int actuallyStolen = impact.protectedHit ? 0 : coreChunkManager.stealPoints(impact.defendingFaction, impact.pointsRequested);
            addToBroadcastAggregate(impact, actuallyStolen);
            processed++;
        }

        ticksSinceLastBroadcast++;
        if (ticksSinceLastBroadcast >= broadcastIntervalTicks) {
            flushBroadcasts();
            ticksSinceLastBroadcast = 0;
        }

        if (droppedQueueEvents > 0 && ticksSinceLastBroadcast == 0) {
            plugin.getLogger().warning("Dropped " + droppedQueueEvents + " raid impacts due to queue pressure. Increase performance.max-core-impact-queue-size or lower TNT rate.");
            droppedQueueEvents = 0;
        }
    }

    private void addToBroadcastAggregate(RaidImpact impact, int pointsStolen) {
        String tntLabel = impact.tntType != null ? impact.tntType.name() : "VANILLA";
        String key = impact.defendingFaction + "|" + impact.attackerName + "|" + tntLabel + "|" + impact.protectedHit;

        AggregatedRaidMessage aggregate = pendingBroadcasts.computeIfAbsent(key, ignored ->
                new AggregatedRaidMessage(impact.defendingFaction, impact.attackerName, impact.tntType, impact.protectedHit));

        aggregate.hitCount++;
        aggregate.totalPointsStolen += pointsStolen;
        aggregate.lastY = impact.y;
    }

    private void flushBroadcasts() {
        if (pendingBroadcasts.isEmpty()) {
            return;
        }

        for (AggregatedRaidMessage aggregate : pendingBroadcasts.values()) {
            if (aggregate.protectedHit) {
                String message = String.format("§c❌ %s raid attempts blocked for §e%s§c (PROTECTED)",
                        formatCount(aggregate.hitCount),
                        aggregate.defendingFaction);
                broadcastRaidAttempt(aggregate.defendingFaction, message, aggregate.lastY);
                continue;
            }

            String tntDisplay = aggregate.tntType != null ? customTNTManager.getDisplayName(aggregate.tntType) : "§fVanilla TNT§r";
            String message = String.format(
                    "§c⚔ %s hit §e%s§c's core (%s) with %s and stole §e%d§c points",
                    aggregate.attackerName,
                    aggregate.defendingFaction,
                    formatCount(aggregate.hitCount),
                    tntDisplay,
                    aggregate.totalPointsStolen
            );
            broadcastRaidAttempt(aggregate.defendingFaction, message, aggregate.lastY);
        }

        pendingBroadcasts.clear();
    }

    private String formatCount(int count) {
        return count + (count == 1 ? " hit" : " hits");
    }

    /**
     * Broadcasts raid attempt to faction members
     */
    private void broadcastRaidAttempt(String factionName, String message, int yCoordinate) {
        String heightBonus = String.format(" (Height Bonus: %.0f%%)", raidingManager.getHeightRewardMultiplier(yCoordinate) * 100);
        Bukkit.broadcast(message + heightBonus, "simplefactionsraiding.notify");
    }

    private static class RaidImpact {
        private final String defendingFaction;
        private final String attackerName;
        private final CustomTNTManager.CustomTNTType tntType;
        private final int pointsRequested;
        private final int y;
        private final boolean protectedHit;

        private RaidImpact(String defendingFaction, String attackerName, CustomTNTManager.CustomTNTType tntType,
                           int pointsRequested, int y, boolean protectedHit) {
            this.defendingFaction = defendingFaction;
            this.attackerName = attackerName;
            this.tntType = tntType;
            this.pointsRequested = pointsRequested;
            this.y = y;
            this.protectedHit = protectedHit;
        }
    }

    private static class AggregatedRaidMessage {
        private final String defendingFaction;
        private final String attackerName;
        private final CustomTNTManager.CustomTNTType tntType;
        private final boolean protectedHit;

        private int hitCount;
        private int totalPointsStolen;
        private int lastY;

        private AggregatedRaidMessage(String defendingFaction, String attackerName,
                                      CustomTNTManager.CustomTNTType tntType, boolean protectedHit) {
            this.defendingFaction = defendingFaction;
            this.attackerName = attackerName;
            this.tntType = tntType;
            this.protectedHit = protectedHit;
            this.hitCount = 0;
            this.totalPointsStolen = 0;
            this.lastY = 64;
        }
    }
}
