package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Custom TNT physics:
 *
 * 1. WATER FLOW CANCELLATION
 *    TNT sitting in water will not drift from water current.
 *    Horizontal velocity below the configured threshold is zeroed each tick when
 *    the TNT occupies a water or waterlogged block.  High-speed (explosion-induced)
 *    horizontal velocity is left untouched so blast-pushed TNT can still travel
 *    through water channels.
 *
 * 2. PARTIAL BLOCK COLLISION
 *    Slabs, trapdoors, fences, walls, stairs, ladders, iron bars and chains stop
 *    TNT on a per-axis basis.  The projected next-tick bounding box of the TNT is
 *    compared against Block#getBoundingBox() (which returns the correct partial
 *    box for top/bottom slabs, open/closed trapdoors, etc.).  Only the velocity
 *    components that actually drive the overlap are zeroed, so TNT that is sliding
 *    along the top of a bottom-slab channel continues to move laterally.
 *
 * 3. PARTIAL BLOCK EXPLOSION EXPOSURE
 *    When a TNTPrimed explodes, this listener captures the pre-explosion velocities
 *    of nearby TNT entities.  One tick later (after vanilla has applied explosion
 *    velocities) the explosion-induced delta for each affected TNT is ray-marched
 *    from the explosion centre to the TNT.  Where that ray passes through a
 *    partial-block bounding box the delta is attenuated proportionally, so bottom
 *    slabs below a TNT reduce the upward force of explosions from below, open
 *    trapdoors acting as blast shields partially deflect lateral force, etc.
 */
public class TNTPhysicsListener implements Listener {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /** Squared horizontal speed below which water-flow cancellation applies. */
    private static final double WATER_CANCEL_SPEED_SQ = 0.2 * 0.2;

    /** Blast radius sampled for nearby TNT on explosion (slightly generous). */
    private static final double BLAST_SAMPLE_RADIUS = 6.0;

    /** Number of ray-march samples per block of distance for exposure. */
    private static final int EXPOSURE_SAMPLES_PER_BLOCK = 6;

    // -----------------------------------------------------------------------
    // Materials that trigger partial-block physics
    // -----------------------------------------------------------------------

    private static final Set<Material> PARTIAL_MATERIALS = buildPartialMaterials();

    private static Set<Material> buildPartialMaterials() {
        Set<Material> set = new HashSet<>();
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_SLAB")
                    || n.endsWith("_TRAPDOOR")
                    || n.endsWith("_FENCE")       // oak_fence, nether_brick_fence …
                    || n.endsWith("_FENCE_GATE")
                    || n.endsWith("_WALL")
                    || n.endsWith("_STAIRS")) {
                set.add(m);
            }
        }
        set.add(Material.LADDER);
        set.add(Material.IRON_BARS);
        return set;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final JavaPlugin plugin;
    private final boolean waterCancelEnabled;
    private final boolean partialBlockEnabled;
    private final boolean exposureEnabled;

    public TNTPhysicsListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.waterCancelEnabled  = plugin.getConfig().getBoolean("cannoning.cancel-water-flow",       true);
        this.partialBlockEnabled = plugin.getConfig().getBoolean("cannoning.partial-block-collision", true);
        this.exposureEnabled     = plugin.getConfig().getBoolean("cannoning.partial-block-exposure",  true);

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAllTNT, 1L, 1L);
    }

    // -----------------------------------------------------------------------
    // Per-tick processing
    // -----------------------------------------------------------------------

    private void tickAllTNT() {
        for (World world : Bukkit.getWorlds()) {
            for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
                if (!tnt.isValid()) continue;

                Vector vel = tnt.getVelocity();
                boolean changed = false;

                // 1. Water flow cancellation
                if (waterCancelEnabled) {
                    Block block = tnt.getLocation().getBlock();
                    if (isInWater(block)) {
                        double hSpeedSq = vel.getX() * vel.getX() + vel.getZ() * vel.getZ();
                        if (hSpeedSq < WATER_CANCEL_SPEED_SQ) {
                            vel.setX(0);
                            vel.setZ(0);
                            changed = true;
                        }
                        // High-speed horizontal: explosion-induced, leave it alone
                    }
                }

                // 2. Partial block collision
                if (partialBlockEnabled) {
                    Vector resolved = resolvePartialCollision(tnt, vel);
                    if (resolved != vel) changed = true;
                    vel = resolved;
                }

                if (changed) {
                    tnt.setVelocity(vel);
                }
            }
        }
    }

    private boolean isInWater(Block block) {
        if (block.getType() == Material.WATER) return true;
        if (block.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl) {
            return wl.isWaterlogged();
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Partial block collision resolution
    // -----------------------------------------------------------------------

    /**
     * Projects where the TNT will be next tick using {@code vel}, then for any
     * partial-block bounding box that the projected AABB overlaps, zeroes the
     * individual velocity axes that cause the overlap.
     * Returns a (possibly new) Vector; if no change was needed the same instance
     * is returned so the caller can detect no-op cheaply.
     */
    private Vector resolvePartialCollision(TNTPrimed tnt, Vector vel) {
        if (vel.lengthSquared() < 1e-8) return vel;

        BoundingBox bb   = tnt.getBoundingBox();
        double halfX = (bb.getWidthX()) / 2.0;
        double halfY = (bb.getHeight()) / 2.0;
        double halfZ = (bb.getWidthZ()) / 2.0;

        double cx = bb.getCenterX() + vel.getX();
        double cy = bb.getCenterY() + vel.getY();
        double cz = bb.getCenterZ() + vel.getZ();

        // AABB at the projected position
        BoundingBox projected = new BoundingBox(cx - halfX, cy - halfY, cz - halfZ,
                                                cx + halfX, cy + halfY, cz + halfZ);

        int minBX = (int) Math.floor(projected.getMinX());
        int maxBX = (int) Math.floor(projected.getMaxX());
        int minBY = (int) Math.floor(projected.getMinY());
        int maxBY = (int) Math.floor(projected.getMaxY());
        int minBZ = (int) Math.floor(projected.getMinZ());
        int maxBZ = (int) Math.floor(projected.getMaxZ());

        World world = tnt.getWorld();
        Vector out = vel; // start with same reference; clone only if we change

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int by = minBY; by <= maxBY; by++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (!PARTIAL_MATERIALS.contains(block.getType())) continue;

                    BoundingBox blockBB = block.getBoundingBox();
                    // Skip passable states (e.g. open fence gate, open-upward trapdoor in some configs)
                    if (blockBB.getVolume() < 1e-6) continue;
                    if (!projected.overlaps(blockBB)) continue;

                    // Determine which axes drive the overlap; test each axis independently.
                    // For axis A: project the TNT moving ONLY along A; if that sub-projection
                    // already overlaps the block, A is a contributing axis -> zero it.

                    if (out == vel) out = vel.clone(); // ensure we don't mutate original

                    double ocx = bb.getCenterX(), ocy = bb.getCenterY(), ocz = bb.getCenterZ();

                    // X axis
                    if (out.getX() != 0) {
                        BoundingBox testX = new BoundingBox(
                            ocx + out.getX() - halfX, ocy - halfY, ocz - halfZ,
                            ocx + out.getX() + halfX, ocy + halfY, ocz + halfZ);
                        if (testX.overlaps(blockBB)) out.setX(0);
                    }

                    // Z axis
                    if (out.getZ() != 0) {
                        BoundingBox testZ = new BoundingBox(
                            ocx - halfX, ocy - halfY, ocz + out.getZ() - halfZ,
                            ocx + halfX, ocy + halfY, ocz + out.getZ() + halfZ);
                        if (testZ.overlaps(blockBB)) out.setZ(0);
                    }

                    // Y axis
                    if (out.getY() != 0) {
                        BoundingBox testY = new BoundingBox(
                            ocx - halfX, ocy + out.getY() - halfY, ocz - halfZ,
                            ocx + halfX, ocy + out.getY() + halfY, ocz + halfZ);
                        if (testY.overlaps(blockBB)) out.setY(0);
                    }
                }
            }
        }

        return out;
    }

    // -----------------------------------------------------------------------
    // Explosion exposure adjustment
    // -----------------------------------------------------------------------

    /**
     * At MONITOR priority the block list has been calculated but entity velocity
     * changes from the explosion have NOT yet been applied, so we can safely
     * snapshot pre-explosion velocities here.  One tick later vanilla will have
     * pushed the nearby TNT entities; we then compute the explosion-induced delta,
     * ray-march through any partial blocks between the explosion and each TNT,
     * and scale the delta down by the fraction of the ray that was inside a
     * partial-block bounding box.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!exposureEnabled) return;
        if (!(event.getEntity() instanceof TNTPrimed)) return;

        Location explosionLoc = event.getLocation();

        // Snapshot: entity -> pre-explosion velocity
        Map<TNTPrimed, Vector> snapshot = new HashMap<>();
        for (Entity e : explosionLoc.getNearbyEntities(
                BLAST_SAMPLE_RADIUS, BLAST_SAMPLE_RADIUS, BLAST_SAMPLE_RADIUS)) {
            if (e instanceof TNTPrimed tnt && tnt.isValid()) {
                snapshot.put(tnt, tnt.getVelocity().clone());
            }
        }

        if (snapshot.isEmpty()) return;

        // One tick later: vanilla has applied explosion velocity; adjust for partial exposure
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<TNTPrimed, Vector> entry : snapshot.entrySet()) {
                TNTPrimed tnt = entry.getKey();
                if (!tnt.isValid()) continue;

                Vector preVel  = entry.getValue();
                Vector postVel = tnt.getVelocity();
                Vector delta   = postVel.clone().subtract(preVel);

                if (delta.lengthSquared() < 1e-5) continue; // barely affected

                // Coverage: 0.0 = fully exposed, 1.0 = fully blocked by partial blocks
                double coverage = rayMarchPartialCoverage(
                        explosionLoc,
                        tnt.getLocation().add(0, 0.49, 0));

                if (coverage > 1e-3) {
                    double exposureFactor = 1.0 - coverage;
                    // New velocity = preExplosion + delta * exposureFactor
                    tnt.setVelocity(preVel.clone().add(delta.multiply(exposureFactor)));
                }
            }
        }, 1L);
    }

    /**
     * Ray-marches from {@code start} to {@code end} sampling at
     * {@link #EXPOSURE_SAMPLES_PER_BLOCK} intervals per block of distance.
     * Returns the fraction of sample points that fall inside a partial-block
     * bounding box (0.0 = fully exposed, 1.0 = fully blocked).
     */
    private double rayMarchPartialCoverage(Location start, Location end) {
        Vector from = start.toVector();
        Vector dir  = end.toVector().subtract(from);
        double length = dir.length();
        if (length < 0.01) return 0.0;
        dir.normalize();

        int totalSamples = Math.max(2, (int) (length * EXPOSURE_SAMPLES_PER_BLOCK));
        int blocked = 0;

        for (int i = 0; i <= totalSamples; i++) {
            double t = length * i / totalSamples;
            Vector point = from.clone().add(dir.clone().multiply(t));

            Block block = start.getWorld().getBlockAt(
                    (int) Math.floor(point.getX()),
                    (int) Math.floor(point.getY()),
                    (int) Math.floor(point.getZ()));

            if (!PARTIAL_MATERIALS.contains(block.getType())) continue;

            BoundingBox bb = block.getBoundingBox();
            if (bb.getVolume() < 1e-6) continue;

            // Block.getBoundingBox() returns absolute world coordinates
            if (bb.contains(point)) blocked++;
        }

        return (double) blocked / (totalSamples + 1);
    }
}
