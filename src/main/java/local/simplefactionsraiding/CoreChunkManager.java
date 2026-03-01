package local.simplefactionsraiding;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import local.simplefactions.FactionManager;
import java.util.*;

/**
 * Manages core chunks and faction claim deletion timers
 * - Core chunk: First claim in a faction, permanent, can't delete, stores points
 * - Regular claims in wild: Auto-delete after 24 hours
 * - Regular claims in warzone: Permanent
 */
public class CoreChunkManager {

    private final JavaPlugin plugin;
    private final FactionManager factionManager;
    private final Map<String, ChunkData> coreChunks = new HashMap<>();
    private final Map<String, ChunkData> coreChunksByLocation = new HashMap<>();
    private final Map<String, Long> claimCreationTimes = new HashMap<>();
    private final long WILD_CLAIM_DURATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    public static class ChunkData {
        public String factionName;
        public int x;
        public int z;
        public World world;
        public int points; // Current points in core chunk

        public ChunkData(String factionName, int x, int z, World world) {
            this.factionName = factionName;
            this.x = x;
            this.z = z;
            this.world = world;
            this.points = 100; // Starting points
        }

        public String getKey() {
            return world.getName() + ":" + x + ":" + z;
        }
    }

    public CoreChunkManager(JavaPlugin plugin, FactionManager factionManager) {
        this.plugin = plugin;
        this.factionManager = factionManager;
    }

    /**
     * Designates a chunk as a faction's core chunk (done on first claim)
     */
    public void setCoreChunk(String factionName, int x, int z, World world) {
        ChunkData existing = coreChunks.get(factionName.toLowerCase());
        if (existing != null) {
            coreChunksByLocation.remove(existing.getKey());
        }

        ChunkData coreData = new ChunkData(factionName, x, z, world);
        coreChunks.put(factionName.toLowerCase(), coreData);
        coreChunksByLocation.put(coreData.getKey(), coreData);
    }

    /**
     * Gets the core chunk data for a faction
     */
    public ChunkData getCoreChunk(String factionName) {
        return coreChunks.get(factionName.toLowerCase());
    }

    /**
     * Checks if a chunk is a core chunk
     */
    public boolean isCoreChunk(int x, int z, World world) {
        return coreChunksByLocation.containsKey(getChunkKey(world, x, z));
    }

    /**
     * Gets core chunk data by chunk location
     */
    public ChunkData getCoreChunkAt(int x, int z, World world) {
        return coreChunksByLocation.get(getChunkKey(world, x, z));
    }

    /**
     * Checks if a chunk is the core for a specific faction
     */
    public boolean isFactionCore(String factionName, int x, int z, World world) {
        ChunkData core = getCoreChunk(factionName);
        return core != null && core.x == x && core.z == z && core.world.equals(world);
    }

    /**
     * Steals points from a faction's core chunk
     */
    public int stealPoints(String factionName, int amount) {
        ChunkData core = getCoreChunk(factionName);
        if (core == null) return 0;

        int stolen = Math.min(amount, core.points);
        core.points -= stolen;
        return stolen;
    }

    /**
     * Adds points to a faction's core chunk
     */
    public void addPoints(String factionName, int amount) {
        ChunkData core = getCoreChunk(factionName);
        if (core != null) {
            core.points += amount;
        }
    }

    /**
     * Gets current points in a faction's core chunk
     */
    public int getPoints(String factionName) {
        ChunkData core = getCoreChunk(factionName);
        return core != null ? core.points : 0;
    }

    /**
     * Registers when a claim was created (for auto-deletion tracking)
     */
    public void registerClaimCreation(String chunkKey) {
        claimCreationTimes.put(chunkKey, System.currentTimeMillis());
    }

    /**
     * Checks if a claim in the wild should be auto-deleted (24 hour rule)
     */
    public boolean shouldAutoDeleteClaim(String chunkKey) {
        if (!claimCreationTimes.containsKey(chunkKey)) {
            return false;
        }

        long createdTime = claimCreationTimes.get(chunkKey);
        long elapsedMs = System.currentTimeMillis() - createdTime;

        return elapsedMs >= WILD_CLAIM_DURATION_MS;
    }

    /**
     * Gets remaining time before a wild claim is auto-deleted (in seconds)
     */
    public long getTimeUntilAutoDelete(String chunkKey) {
        if (!claimCreationTimes.containsKey(chunkKey)) {
            return WILD_CLAIM_DURATION_MS / 1000;
        }

        long createdTime = claimCreationTimes.get(chunkKey);
        long elapsedMs = System.currentTimeMillis() - createdTime;
        long remainingMs = WILD_CLAIM_DURATION_MS - elapsedMs;

        return Math.max(0, remainingMs / 1000);
    }

    /**
     * Removes a claim from tracking
     */
    public void unregisterClaim(String chunkKey) {
        claimCreationTimes.remove(chunkKey);
    }

    /**
     * Gets all core chunks
     */
    public Collection<ChunkData> getAllCoreChunks() {
        return Collections.unmodifiableCollection(coreChunks.values());
    }

    private String getChunkKey(World world, int x, int z) {
        return world.getName() + ":" + x + ":" + z;
    }
}
