package local.simplefactionsraiding;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/**
 * Manages raid time windows for factions.
 * Each faction has an 8-hour raid window (active) and 16-hour protected window (inactive).
 */
public class RaidingManager {

    private final JavaPlugin plugin;
    private final Map<String, Long> factionRaidStartTimes = new HashMap<>();
    private final long RAID_DURATION_TICKS = 8 * 60 * 60 * 20; // 8 hours in ticks
    private final long PROTECTED_DURATION_TICKS = 16 * 60 * 60 * 20; // 16 hours in ticks

    public RaidingManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes a faction's raid window with current time
     */
    public void initializeRaidWindow(String factionName) {
        factionRaidStartTimes.put(factionName, System.currentTimeMillis());
    }

    /**
     * Checks if a faction is currently in raid window (active raiding)
     */
    public boolean isRaidActive(String factionName) {
        if (!factionRaidStartTimes.containsKey(factionName)) {
            initializeRaidWindow(factionName);
        }

        long startTime = factionRaidStartTimes.get(factionName);
        long elapsedMs = System.currentTimeMillis() - startTime;
        long raidCycleTicks = (RAID_DURATION_TICKS + PROTECTED_DURATION_TICKS) * 50; // Convert to ms

        long positionInCycle = elapsedMs % raidCycleTicks;
        long raidDurationMs = RAID_DURATION_TICKS * 50;

        return positionInCycle < raidDurationMs;
    }

    /**
     * Gets the time remaining in current raid window (in seconds)
     */
    public long getTimeRemainingInWindow(String factionName) {
        if (!factionRaidStartTimes.containsKey(factionName)) {
            initializeRaidWindow(factionName);
        }

        long startTime = factionRaidStartTimes.get(factionName);
        long elapsedMs = System.currentTimeMillis() - startTime;
        long raidCycleTicks = (RAID_DURATION_TICKS + PROTECTED_DURATION_TICKS) * 50;

        long positionInCycle = elapsedMs % raidCycleTicks;
        long raidDurationMs = RAID_DURATION_TICKS * 50;

        if (positionInCycle < raidDurationMs) {
            return (raidDurationMs - positionInCycle) / 1000;
        } else {
            long protectedDurationMs = PROTECTED_DURATION_TICKS * 50;
            return (protectedDurationMs - (positionInCycle - raidDurationMs)) / 1000;
        }
    }

    /**
     * Gets the status (RAID or PROTECTED) for a faction
     */
    public String getRaidStatus(String factionName) {
        return isRaidActive(factionName) ? "§c⚔ RAID" : "§a⛔ PROTECTED";
    }

    /**
     * Resets a faction's raid window (for admin commands)
     */
    public void resetRaidWindow(String factionName) {
        factionRaidStartTimes.put(factionName, System.currentTimeMillis());
    }

    /**
     * Manually set a faction into raid mode
     */
    public void setRaidMode(String factionName, boolean active) {
        long now = System.currentTimeMillis();
        if (active) {
            factionRaidStartTimes.put(factionName, now);
        } else {
            // Set to middle of protected window (so it won't enter raid for a while)
            long raidDurationMs = RAID_DURATION_TICKS * 50;
            factionRaidStartTimes.put(factionName, now - raidDurationMs);
        }
    }

    /**
     * Calculates height-based reward multiplier (Y=319 = 75%, Y=1 = 5%)
     * Linear interpolation between Y=1 (5%) and Y=319 (75%)
     */
    public double getHeightRewardMultiplier(int yCoordinate) {
        final int MIN_Y = 1;
        final int MAX_Y = 319;
        final double MIN_REWARD = 0.05; // 5%
        final double MAX_REWARD = 0.75; // 75%

        if (yCoordinate <= MIN_Y) return MIN_REWARD;
        if (yCoordinate >= MAX_Y) return MAX_REWARD;

        double range = MAX_REWARD - MIN_REWARD;
        double normalized = (double) (yCoordinate - MIN_Y) / (MAX_Y - MIN_Y);
        return MIN_REWARD + (normalized * range);
    }

    /**
     * Calculates points stolen based on core chunk and Y-coordinate
     */
    public int calculatePointsStolen(int maxCorePoints, int yCoordinate) {
        double multiplier = getHeightRewardMultiplier(yCoordinate);
        return (int) (maxCorePoints * multiplier);
    }
}
