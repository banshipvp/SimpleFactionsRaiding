package local.simplefactionsraiding;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Squared horizontal speed below which water-flow drift cancellation applies. */
    private static final double WATER_CANCEL_SPEED_SQ = 0.2 * 0.2;

    /**
     * Speed threshold (blocks/tick) above which TNT is considered explosion-launched.
     * Partial-block collision is skipped for fast-moving TNT to avoid incorrectly
     * stalling cannon projectiles inside waterlogged ladders or other partial blocks.
     * Also used to detect a fresh same-tick blast hit by comparing actualVel.
     */
    private static final double PARTIAL_SPEED_THRESHOLD_SQ = 0.09; // 0.3 b/t squared

    /**
     * Once TNT is sliding, it continues to decelerate naturally until horizontal
     * speed drops below this threshold, at which point it is considered stopped.
     * Much lower than PARTIAL_SPEED_THRESHOLD_SQ so the slide fully plays out.
     */
    private static final double LAUNCH_CLEAR_SPEED_SQ = 0.0001; // 0.01 b/t squared

    /** Blast radius sampled for nearby TNT on explosion (slightly generous). */
    private static final double BLAST_SAMPLE_RADIUS = 6.0;

    /** Number of ray-march samples per block of distance for exposure. */
    private static final int EXPOSURE_SAMPLES_PER_BLOCK = 8;

    // -----------------------------------------------------------------------
    // Materials that trigger partial-block physics
    // -----------------------------------------------------------------------

    private static final Set<Material> PARTIAL_MATERIALS = buildPartialMaterials();

    private static Set<Material> buildPartialMaterials() {
        Set<Material> set = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_SLAB")
                    || n.endsWith("_TRAPDOOR")
                    || n.endsWith("_FENCE")       // oak_fence, nether_brick_fence …
                    || n.endsWith("_FENCE_GATE")
                    || n.endsWith("_WALL")
                    || n.endsWith("_STAIRS")
                    || n.endsWith("_GLASS_PANE")) {
                set.add(m);
            }
        }
        set.add(Material.LADDER);
        set.add(Material.IRON_BARS);
        set.add(Material.GLASS_PANE);
        set.add(Material.CHAIN);
        set.add(Material.END_ROD);
        set.add(Material.ENCHANTING_TABLE);
        set.add(Material.BREWING_STAND);
        set.add(Material.CONDUIT);
        set.add(Material.LANTERN);
        set.add(Material.SOUL_LANTERN);
        set.add(Material.BELL);
        return set;
    }

    // -----------------------------------------------------------------------
    // Per-block exposure weights
    // -----------------------------------------------------------------------

    /**
     * Fraction of blast energy blocked (0.0 = fully transparent, 1.0 = fully opaque)
     * when a ray-march sample point falls inside the block's bounding box.
     *
     * These represent the approximate material density within the bounding box:
     *   - A bottom slab is 50 % of the bounding-box volume, so weight = 0.5
     *   - A trapdoor only occupies 3/16 of its block → weight ≈ 0.19
     *   - A ladder is solid rungs across its blocking axis → weight = 0.9
     *   - Iron bars are very thin → weight = 0.3
     * For blocks not explicitly listed the fallback is 0.5.
     */
    private static final Map<Material, Double> BLOCK_EXPOSURE_WEIGHT = buildExposureWeights();

    private static Map<Material, Double> buildExposureWeights() {
        Map<Material, Double> map = new EnumMap<>(Material.class);
        // Ladders – dense rungs on the blocking axis
        map.put(Material.LADDER,            0.90);
        // Iron bars / glass panes – thin vertical elements
        map.put(Material.IRON_BARS,         0.30);
        map.put(Material.GLASS_PANE,        0.20);
        // Chain – thin links
        map.put(Material.CHAIN,             0.25);
        // End rod – very thin pole
        map.put(Material.END_ROD,           0.12);
        // Enchanting table – chunky centre block
        map.put(Material.ENCHANTING_TABLE,  0.75);
        // Brewing stand – thin rods
        map.put(Material.BREWING_STAND,     0.20);
        // Conduit / bell / lanterns
        map.put(Material.CONDUIT,           0.55);
        map.put(Material.LANTERN,           0.40);
        map.put(Material.SOUL_LANTERN,      0.40);
        map.put(Material.BELL,              0.60);
        // Suffix-matched types — fill in by scanning all materials
        for (Material m : Material.values()) {
            String n = m.name();
            if (map.containsKey(m)) continue;
            if (n.endsWith("_SLAB"))        { map.put(m, 0.50); continue; }
            if (n.endsWith("_TRAPDOOR"))    { map.put(m, 0.19); continue; }
            if (n.endsWith("_FENCE_GATE"))  { map.put(m, 0.28); continue; }
            if (n.endsWith("_FENCE"))       { map.put(m, 0.45); continue; }
            if (n.endsWith("_WALL"))        { map.put(m, 0.55); continue; }
            if (n.endsWith("_STAIRS"))      { map.put(m, 0.50); continue; }
            if (n.endsWith("_GLASS_PANE"))  { map.put(m, 0.20); continue; }
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final JavaPlugin plugin;
    private final boolean waterCancelEnabled;
    private final boolean partialBlockEnabled;
    private final boolean exposureEnabled;

    /**
     * Tracked live TNT entities. Maintained via EntitySpawnEvent / EntityExplodeEvent
     * so we never have to scan the whole world entity list every tick.
     */
    private final Set<UUID> trackedTNT = ConcurrentHashMap.newKeySet();

    /**
     * Tracks the velocity we set for each TNT entity at the end of the previous
     * tick. Used to recompute air physics in water: vanilla applies water drag
     * (0.8×) and buoyancy before our handler runs, so we can't trust the value
     * returned by getVelocity() when the entity is submerged.  By keeping our
     * own record we can apply the correct air formula (×0.98, −0.04 Y) both for
     * slow (stationary) and fast (explosion-launched) TNT that passes through water.
     */
    private final Map<UUID, Vector> prevVel = new HashMap<>();

    /**
     * Tracks TNT that has been explosion-launched. Once a TNT enters this set it
     * continues to use air-physics (instead of being pinned to zero) until its
     * horizontal speed drops below {@link #LAUNCH_CLEAR_SPEED_SQ}. This prevents
     * abrupt stops when the speed crosses {@link #PARTIAL_SPEED_THRESHOLD_SQ} and
     * correctly handles the common same-tick case where the explosion fires in the
     * same game tick as tickAllTNT but AFTER prevVel was already set to near-zero.
     */
    private final Set<UUID> launchedTNT = ConcurrentHashMap.newKeySet();

    /**
     * When true, TNT explosions are cancelled before they fire particles/sounds.
     * A simplified blast velocity is applied manually so cannon chains still work.
     * Toggle via /tntparticles.
     */
    private volatile boolean suppressExplosions = false;
    public boolean isSuppressingExplosions() { return suppressExplosions; }
    public void setSuppressExplosions(boolean suppress) { this.suppressExplosions = suppress; }

    public TNTPhysicsListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.waterCancelEnabled  = plugin.getConfig().getBoolean("cannoning.cancel-water-flow",       true);
        this.partialBlockEnabled = plugin.getConfig().getBoolean("cannoning.partial-block-collision", true);
        this.exposureEnabled     = plugin.getConfig().getBoolean("cannoning.partial-block-exposure",  true);

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAllTNT, 1L, 1L);
    }

    // -----------------------------------------------------------------------
    // TNT entity tracking
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTNTSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof TNTPrimed tnt) {
            UUID uuid = tnt.getUniqueId();
            trackedTNT.add(uuid);
            // Seed prevVel to zero so the first physics tick doesn't inherit
            // whatever vanilla set (which may be water-affected).
            prevVel.put(uuid, new Vector(0, 0, 0));
            // 4-second fuse (80 ticks) for all TNT entities.
            tnt.setFuseTicks(80);
        }
    }

    // -----------------------------------------------------------------------
    // Per-tick processing
    // -----------------------------------------------------------------------

    private void tickAllTNT() {
        if (trackedTNT.isEmpty()) return;

        // Iterate a snapshot of tracked UUIDs; remove stale entries
        trackedTNT.removeIf(uuid -> {
            Entity e = plugin.getServer().getEntity(uuid);
            if (e == null || !e.isValid()) {
                prevVel.remove(uuid);
                return true; // remove stale
            }
            tickOneTNT((TNTPrimed) e);
            return false;
        });
    }

    private void tickOneTNT(TNTPrimed tnt) {
        UUID uuid = tnt.getUniqueId();
        // What vanilla actually set this tick (may be water-corrupted).
        Vector actualVel = tnt.getVelocity();
        // What WE set last tick — the clean baseline for air-physics calculations.
        Vector prev = prevVel.getOrDefault(uuid, new Vector(0, 0, 0));

        boolean inWater = isInWater(tnt.getLocation().getBlock());

        // ----------------------------------------------------------------
        // Explosion-launch detection
        // ----------------------------------------------------------------
        // We check TWO conditions with OR:
        //   a) prevHSq: we set a high horizontal velocity last tick → still coasting
        //   b) diffHSq: actual velocity differs significantly from our baseline →
        //      an explosion pushed this TNT THIS tick before our handler ran.
        //      Water currents are weak (~0.04-0.07/tick) so the threshold cleanly
        //      separates water drift from real blast forces.
        double prevHSq = prev.getX() * prev.getX() + prev.getZ() * prev.getZ();
        double diffX   = actualVel.getX() - prev.getX();
        double diffZ   = actualVel.getZ() - prev.getZ();
        double diffHSq = diffX * diffX + diffZ * diffZ;

        boolean launchedByExplosion = prevHSq >= PARTIAL_SPEED_THRESHOLD_SQ
                                   || diffHSq >= PARTIAL_SPEED_THRESHOLD_SQ * 0.5;

        double nx, ny, nz;

        if (launchedByExplosion) {
            // Choose the best velocity baseline:
            //   - If explosion just hit (diffHSq large), actualVel is the raw blast → use it.
            //   - If coasting from prior tick (prevHSq large), prev is our clean value → replay air physics.
            boolean explosionThisTick = diffHSq >= PARTIAL_SPEED_THRESHOLD_SQ * 0.5;
            if (inWater) {
                // Replay air physics on whichever baseline has more horizontal energy.
                Vector base = explosionThisTick ? actualVel : prev;
                nx = base.getX() * 0.98;
                ny = (base.getY() - 0.04) * 0.98;
                nz = base.getZ() * 0.98;
            } else {
                // In air — vanilla air physics are correct, trust them.
                nx = actualVel.getX();
                ny = actualVel.getY();
                nz = actualVel.getZ();
            }
        } else {
            // Not explosion-launched: pin X and Z to zero.
            nx = 0;
            nz = 0;

            if (inWater) {
                // Vanilla has already moved the entity by actualVel this tick.
                // Undo any X/Z position drift introduced by the water current.
                double wx = actualVel.getX();
                double wz = actualVel.getZ();
                if (Math.abs(wx) > 0.002 || Math.abs(wz) > 0.002) {
                    Location loc = tnt.getLocation();
                    tnt.teleport(loc.clone().subtract(wx, 0, wz));
                }

                // Override water buoyancy/drag with vanilla air-gravity formula.
                double candidateY = (prev.getY() - 0.04) * 0.98;
                // Cap at zero if resting on a solid surface so we don't bounce.
                if (candidateY < 0 && isSolidBelow(tnt)) {
                    candidateY = 0;
                }
                ny = candidateY;
            } else {
                // In air — vanilla handles Y correctly (gravity + collision response).
                ny = actualVel.getY();

                // Partial block collision for slow, non-launched TNT in air.
                if (partialBlockEnabled) {
                    Vector resolved = resolvePartialCollision(tnt, new Vector(nx, ny, nz));
                    nx = resolved.getX();
                    ny = resolved.getY();
                    nz = resolved.getZ();
                }
            }
        }

        Vector newVel = new Vector(nx, ny, nz);
        prevVel.put(uuid, newVel);
        tnt.setVelocity(newVel);
    }

    /** Returns true if there is a solid block directly beneath the TNT entity. */
    private boolean isSolidBelow(TNTPrimed tnt) {
        Block below = tnt.getLocation().subtract(0, 0.1, 0).getBlock();
        return below.getType().isSolid();
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

                    if (block.getType() == Material.LADDER) {
                        // Ladders are wall-mounted: only block the single axis perpendicular
                        // to their attachment wall.  Y is never blocked — TNT falling down past
                        // (or onto) a ladder must not be caught by it from above.
                        // getFacing() returns the direction AWAY from the wall (into the room):
                        //   EAST/WEST facing  → attached to west/east  wall → blocks X axis
                        //   NORTH/SOUTH facing → attached to south/north wall → blocks Z axis
                        BlockFace ladderFacing = ((Directional) block.getBlockData()).getFacing();
                        boolean ladderBlocksX = ladderFacing == BlockFace.EAST
                                || ladderFacing == BlockFace.WEST;
                        if (ladderBlocksX) {
                            if (out.getX() != 0) {
                                BoundingBox testX = new BoundingBox(
                                    ocx + out.getX() - halfX, ocy - halfY, ocz - halfZ,
                                    ocx + out.getX() + halfX, ocy + halfY, ocz + halfZ);
                                if (testX.overlaps(blockBB)) out.setX(0);
                            }
                        } else {
                            if (out.getZ() != 0) {
                                BoundingBox testZ = new BoundingBox(
                                    ocx - halfX, ocy - halfY, ocz + out.getZ() - halfZ,
                                    ocx + halfX, ocy + halfY, ocz + out.getZ() + halfZ);
                                if (testZ.overlaps(blockBB)) out.setZ(0);
                            }
                        }
                        continue; // never check Y — ladders cannot catch TNT from above
                    }

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
    // Water flow: prevent waterlogging ladders
    // -----------------------------------------------------------------------

    /**
     * Prevents water from flowing into a block occupied by a ladder.
     * Ladders are used as cannon stoppers; waterlogging them causes water to
     * escape into unintended areas and breaks stopper geometry.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWaterFlowIntoLadder(BlockFromToEvent event) {
        if (event.getToBlock().getType() == Material.LADDER) {
            event.setCancelled(true);
        }
    }

    // -----------------------------------------------------------------------
    // Water-submerged TNT: no block damage
    // -----------------------------------------------------------------------

    /**
     * Always: cancel TNT damage and knockback to players.
     * Players should never be harmed or thrown by TNT explosions regardless
     * of whether particle suppression is on or off.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTNTDamagePlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof TNTPrimed && event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /**
     * If a TNTPrimed entity is located inside a water block (or a waterlogged block)
     * when it explodes, clear the block break list entirely.  The explosion still
     * fires — entity damage and velocity applied to nearby TNT are unaffected — but
     * no blocks are destroyed.
     *
     * This covers the ladder-adjacent-to-water case: the TNT occupies the water
     * block, so the ladder is shielded even though the ladder itself is not
     * waterlogged.
     *
     * Priority is HIGH so vanilla has already assembled the block list, and our
     * MONITOR handler (partial-block exposure) still runs afterwards to adjust
     * neighbouring TNT velocities correctly.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTNTExplodeInWater(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        // Always clean up tracking when TNT explodes
        UUID eid = tnt.getUniqueId();
        trackedTNT.remove(eid);
        prevVel.remove(eid);
        launchedTNT.remove(eid);

        Block tntBlock = event.getLocation().getBlock();
        if (isInWater(tntBlock)) {
            event.blockList().clear();
        }
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

        // One tick later: vanilla has applied explosion velocity; adjust for partial exposure.
        // We also update our prevVel record so the physics tick handler on the SAME tick
        // will have a correct baseline (prevents same-tick mismatch from killing the velocity).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<TNTPrimed, Vector> entry : snapshot.entrySet()) {
                TNTPrimed tnt = entry.getKey();
                if (!tnt.isValid()) continue;

                Vector preVel  = entry.getValue();
                // Read the velocity that is currently on the entity.  Our tick handler
                // may have already run this tick and set a clean air-physics value;
                // that is fine — we still want to apply the exposure attenuation.
                Vector postVel = tnt.getVelocity();
                Vector delta   = postVel.clone().subtract(preVel);

                if (delta.lengthSquared() < 1e-5) continue; // barely affected

                // Per-axis coverage: X/Y/Z each independently attenuated based on
                // what partial blocks lie between the explosion and the TNT.
                // Ladders only attenuate their perpendicular axis; other blocks
                // attenuate all axes proportionally.
                Vector axisCov = rayMarchAxisCoverage(
                        explosionLoc,
                        tnt.getLocation().add(0, 0.49, 0));

                double expX = 1.0 - axisCov.getX();
                double expY = 1.0 - axisCov.getY();
                double expZ = 1.0 - axisCov.getZ();

                // Only bother if at least one axis has meaningful attenuation
                if (expX >= 1.0 - 1e-3 && expY >= 1.0 - 1e-3 && expZ >= 1.0 - 1e-3) continue;

                // New velocity = preExplosion + delta * per-axis-exposure
                Vector newVel = preVel.clone().add(new Vector(
                        delta.getX() * expX,
                        delta.getY() * expY,
                        delta.getZ() * expZ));

                // Sync our velocity record so the next physics tick has a good baseline.
                syncVelocityFromExternal(tnt.getUniqueId(), newVel);
                tnt.setVelocity(newVel);
            }
        }, 1L);
    }
    /**
     * Ray-marches from {@code start} to {@code end} and returns a per-axis
     * coverage vector (X, Y, Z) where each component is in [0.0, 1.0]:
     *   0.0 = fully exposed on that axis
     *   1.0 = fully blocked on that axis
     *
     * Coverage is weighted by {@link #BLOCK_EXPOSURE_WEIGHT} so different
     * partial-block types have different opacities.
     *
     * Ladders only block the single horizontal axis perpendicular to their
     * facing direction - they contribute zero coverage on the other two axes.
     * All other partial blocks contribute uniformly to every axis.
     *
     * The final 1.0 block near {@code end} is excluded from the sample so
     * stopper blocks immediately behind the target TNT do not incorrectly
     * shield it from a blast coming from the opposite direction.
     */
    private Vector rayMarchAxisCoverage(Location start, Location end) {
        Vector from = start.toVector();
        Vector to   = end.toVector();
        Vector dir  = to.clone().subtract(from);
        double length = dir.length();
        if (length < 0.01) return new Vector(0, 0, 0);
        dir.normalize();

        // Exclude the final block so back-side stoppers do not penalise the target.
        double sampleLength = Math.max(0.0, length - 1.0);
        if (sampleLength < 0.01) return new Vector(0, 0, 0);

        int totalSamples = Math.max(2, (int) (sampleLength * EXPOSURE_SAMPLES_PER_BLOCK));

        double covX = 0.0, covY = 0.0, covZ = 0.0;

        for (int i = 0; i <= totalSamples; i++) {
            double t = sampleLength * i / totalSamples;
            Vector point = from.clone().add(dir.clone().multiply(t));

            Block block = start.getWorld().getBlockAt(
                    (int) Math.floor(point.getX()),
                    (int) Math.floor(point.getY()),
                    (int) Math.floor(point.getZ()));

            if (!PARTIAL_MATERIALS.contains(block.getType())) continue;

            BoundingBox bb = block.getBoundingBox();
            if (bb.getVolume() < 1e-6) continue;
            if (!bb.contains(point)) continue;

            double weight = BLOCK_EXPOSURE_WEIGHT.getOrDefault(block.getType(), 0.50);

            if (block.getType() == Material.LADDER
                    && block.getBlockData() instanceof Directional ladderDir) {
                // Ladders physically block only the axis perpendicular to their face.
                // getFacing() returns the direction INTO the room (away from wall):
                //   EAST / WEST  facing -> attached to west/east  wall -> blocks X
                //   NORTH / SOUTH facing -> attached to south/north wall -> blocks Z
                // Y is never blocked - a ladder does not stop vertical blast.
                BlockFace facing = ladderDir.getFacing();
                if (facing == BlockFace.EAST || facing == BlockFace.WEST) {
                    covX += weight;
                    // covY and covZ unaffected - blast passes through on those axes
                } else {
                    covZ += weight;
                    // covX and covY unaffected
                }
            } else {
                // All other partial blocks attenuate every blast axis.
                covX += weight;
                covY += weight;
                covZ += weight;
            }
        }

        double n = totalSamples + 1.0;
        return new Vector(
                Math.min(1.0, covX / n),
                Math.min(1.0, covY / n),
                Math.min(1.0, covZ / n));
    }
}