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

import java.lang.reflect.Field;
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
                    || n.endsWith("_FENCE")       // oak_fence, nether_brick_fence â€¦
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
        { Material m = Material.getMaterial("CHAIN"); if (m != null) set.add(m); }
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
     *   - A trapdoor only occupies 3/16 of its block â†’ weight â‰ˆ 0.19
     *   - A ladder is solid rungs across its blocking axis â†’ weight = 0.9
     *   - Iron bars are very thin â†’ weight = 0.3
     * For blocks not explicitly listed the fallback is 0.5.
     */
    private static final Map<Material, Double> BLOCK_EXPOSURE_WEIGHT = buildExposureWeights();

    private static Map<Material, Double> buildExposureWeights() {
        Map<Material, Double> map = new EnumMap<>(Material.class);
        // Ladders â€“ dense rungs on the blocking axis
        map.put(Material.LADDER,            0.90);
        // Iron bars / glass panes â€“ thin vertical elements
        map.put(Material.IRON_BARS,         0.30);
        map.put(Material.GLASS_PANE,        0.20);
        // Chain â€“ thin links
        { Material chain = Material.getMaterial("CHAIN"); if (chain != null) map.put(chain, 0.25); }
        // End rod â€“ very thin pole
        map.put(Material.END_ROD,           0.12);
        // Enchanting table â€“ chunky centre block
        map.put(Material.ENCHANTING_TABLE,  0.75);
        // Brewing stand â€“ thin rods
        map.put(Material.BREWING_STAND,     0.20);
        // Conduit / bell / lanterns
        map.put(Material.CONDUIT,           0.55);
        map.put(Material.LANTERN,           0.40);
        map.put(Material.SOUL_LANTERN,      0.40);
        map.put(Material.BELL,              0.60);
        // Suffix-matched types â€” fill in by scanning all materials
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
     * (0.8Ã—) and buoyancy before our handler runs, so we can't trust the value
     * returned by getVelocity() when the entity is submerged.  By keeping our
     * own record we can apply the correct air formula (Ã—0.98, âˆ’0.04 Y) both for
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
     * Canonical 3-D position {x, y, z} for water-locked TNT (not explosion-launched).
     * Vanilla water current displaces X/Z and buoyancy shifts Y every tick; we
     * teleport back to this saved position at the start of each tick, advancing Y
     * ourselves using the air-gravity formula.  Entries are created on the first tick
     * the TNT is pinned in water and removed when it becomes explosion-launched or
     * is destroyed.
     */
    private final Map<UUID, double[]> pinnedPos = new HashMap<>();

    /**
     * Clean 3-D position {x, y, z} for explosion-launched TNT travelling through water.
     * Vanilla water physics corrupt both position (current + buoyancy) and velocity
     * (0.8Ã— drag) while the TNT is submerged.  We maintain our own position record
     * and advance it with air physics each tick, teleporting the entity there so
     * water has no effect on the trajectory.  Cleared when the TNT leaves water or
     * its horizontal speed decays below {@link #LAUNCH_CLEAR_SPEED_SQ}.
     */
    private final Map<UUID, double[]> launchedPos = new HashMap<>();

    /**
     * When true, TNT explosions are cancelled before they fire particles/sounds.
     * A simplified blast velocity is applied manually so cannon chains still work.
     * Toggle via /tntparticles.
     */
    private volatile boolean suppressExplosions = false;
    public boolean isSuppressingExplosions() { return suppressExplosions; }
    public void setSuppressExplosions(boolean suppress) { this.suppressExplosions = suppress; }

    // -----------------------------------------------------------------------
    // NMS noPhysics — the AtlasSpigot equivalent of isPushedByFluid()=false
    // -----------------------------------------------------------------------

    /**
     * Cache the reflected noPhysics field on first use.
     * In Paper 1.21 (Mojang-mapped), the field lives on net.minecraft.world.entity.Entity
     * and is named "noPhysics" (public boolean).  With noPhysics=true, vanilla
     * Entity.move() returns immediately without applying ANY physics (gravity, water
     * buoyancy, water flow, block collisions).  Bukkit teleport() bypasses move() and
     * still works, so we retain full control over position via teleportation.
     */
    private static Field noPhysicsField = null;

    private static void setNoPhysics(Entity entity, boolean value) {
        try {
            // CraftEntity exposes getHandle() which returns the NMS entity.
            Object nmsEntity = entity.getClass().getMethod("getHandle").invoke(entity);
            if (noPhysicsField == null) {
                // Walk class hierarchy to find the field (it's on a superclass).
                Class<?> cls = nmsEntity.getClass();
                while (cls != null) {
                    try {
                        Field f = cls.getDeclaredField("noPhysics");
                        f.setAccessible(true);
                        noPhysicsField = f;
                        break;
                    } catch (NoSuchFieldException ignored) {
                        cls = cls.getSuperclass();
                    }
                }
            }
            if (noPhysicsField != null) {
                noPhysicsField.set(nmsEntity, value);
            }
        } catch (Exception ignored) {
            // Reflection failed — fall back gracefully (teleport approach still runs)
        }
    }

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

            // Zero velocity immediately — prevents any fluid/gravity force from
            // applying before our first per-tick handler runs.
            tnt.setVelocity(new Vector(0, 0, 0));
            prevVel.put(uuid, new Vector(0, 0, 0));

            // Re-center the entity at the floor-center of its current block.
            // Vanilla dispensers spawn TNT at an offset from the dispenser face
            // (e.g. center + facing * 0.6 + random jitter) which can leave the
            // bounding box clipping into an adjacent block.  Teleporting to the
            // exact floor-center (X+0.5, Y+0.0, Z+0.5) of the block the entity
            // is currently in guarantees the 0.98x0.98 bbox fits cleanly inside
            // the water or air block with no overlap above or below.
            // We only do this for non-solid blocks (water, air) — if somehow the
            // entity spawned inside glass, leave it to vanilla's push-out logic.
            Block entBlock = tnt.getLocation().getBlock();
            if (!entBlock.getType().isSolid()) {
                Location blockCenter = entBlock.getLocation().add(0.5, 0.0, 0.5);
                tnt.teleport(blockCenter);
            }

            // 4-second fuse (80 ticks) for all TNT entities.
            tnt.setFuseTicks(80);

            // If the TNT is in water, pin it immediately.
            if (isTNTInWater(tnt)) {
                Location spawnLoc = tnt.getLocation();
                pinnedPos.put(uuid, new double[]{spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ()});
                // noPhysics=true: vanilla Entity.move() is short-circuited entirely
                // so buoyancy, water-current, and gravity produce no displacement.
                // This is the AtlasSpigot equivalent of isPushedByFluid()=false.
                // The per-tick teleport (below) is a belt-and-suspenders backup in
                // case NMS reflection fails in this Paper build.
                setNoPhysics(tnt, true);
                tnt.setGravity(false);
            }
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
                launchedTNT.remove(uuid);
                pinnedPos.remove(uuid);
                launchedPos.remove(uuid);
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
        // What WE set last tick â€” the clean baseline for air-physics calculations.
        Vector prev = prevVel.getOrDefault(uuid, new Vector(0, 0, 0));

        boolean inWater = isTNTInWater(tnt);

        // noPhysics=true while in water: vanilla Entity.move() is skipped entirely,
        // so water buoyancy, flow, and gravity produce zero position change.
        // noPhysics=false in air: vanilla handles gravity + block collision normally.
        // setGravity mirrors this for defensive completeness.
        setNoPhysics(tnt, inWater);
        tnt.setGravity(!inWater);

        // ----------------------------------------------------------------
        // Explosion-launch detection
        // ----------------------------------------------------------------
        // We check TWO conditions with OR:
        //   a) prevHSq: we set a high horizontal velocity last tick â†’ still coasting
        //   b) diffHSq: actual velocity differs significantly from our baseline â†’
        //      an explosion pushed this TNT THIS tick before our handler ran.
        //      Water currents are weak (~0.04-0.07/tick) so the threshold cleanly
        //      separates water drift from real blast forces.
        double prevHSq = prev.getX() * prev.getX() + prev.getZ() * prev.getZ();

        // Explosion-launch is detected via two reliable signals only:
        //   a) launchedTNT set  -- the MONITOR explosion-event handler
        //      (syncVelocityFromExternal) marks this entity before our scheduler
        //      tick runs, so it is always up-to-date for real blast events.
        //   b) prevHSq -- the velocity WE set last tick was already high, meaning
        //      the TNT is still coasting from an earlier explosion.
        //
        // WHY NO diffHSq THRESHOLD:
        //   With noPhysics=true the entity cannot physically move from fluid forces,
        //   but Minecraft still writes water current + buoyancy into deltaMovement
        //   BEFORE calling Entity.move() (which noPhysics short-circuits).  So
        //   tnt.getVelocity() can return a non-zero fluid-contaminated vector even
        //   when the entity is perfectly stationary in a water pot.
        //   A diffHSq threshold of 0.001 was matching these fluid-velocity deltas
        //   (~0.01-0.05 b/t per axis for a normal water current) and falsely marking
        //   water-pot TNT as explosion-launched.  Once in "launched" mode the
        //   air-gravity teleport path applies -0.04 Y per tick, making the TNT
        //   visibly sink; once that state clears the entity re-pins at the wrong
        //   lower Y and buoyancy snaps it back up -- the exact "down/glitch-up"
        //   and XZ water-drift issues users reported.
        boolean launchedByExplosion = launchedTNT.contains(uuid)
                                   || prevHSq >= PARTIAL_SPEED_THRESHOLD_SQ;

        double nx, ny, nz;

        if (launchedByExplosion) {
            // Mark as launched so future ticks retain launch state even if
            // drag brings prevHSq below the detection threshold.
            launchedTNT.add(uuid);
            // Remove from stationary-water pin - TNT is now free-flying.
            pinnedPos.remove(uuid);

            if (inWater) {
                // Pure teleport-based air physics in water.
                // We zero velocity so vanilla water drag/buoyancy cannot move the entity.
                // Instead we teleport one air-physics step forward each tick.
                // prevVel stores the clean air velocity (never water-corrupted).
                double[] lp = launchedPos.get(uuid);

                if (lp == null) {
                    // First tick after explosion in water.
                    // With noPhysics=true the entity never moved from its anchor, so
                    // curLoc IS the correct launch origin.
                    // prevVel already holds the ATTENUATED blast velocity set by
                    // syncVelocityFromExternal — do NOT overwrite it with actualVel
                    // (which is the raw, unattenuated blast vel still sitting in the
                    // entity's deltaMovement because noPhysics prevented it from being
                    // consumed).  Leave prevVel as-is; the next iteration uses it.
                    Location curLoc = tnt.getLocation();
                    launchedPos.put(uuid, new double[]{curLoc.getX(), curLoc.getY(), curLoc.getZ()});
                    tnt.setVelocity(new Vector(0, 0, 0));
                    return;
                }

                // Advance clean air trajectory (gravity -0.04, drag 0.98).
                double ax = prev.getX() * 0.98;
                double ay = (prev.getY() - 0.04) * 0.98;
                double az = prev.getZ() * 0.98;

                double destX = lp[0] + ax;
                double destY = lp[1] + ay;
                double destZ = lp[2] + az;

                // Sweep the intended trajectory for block collisions.
                // This prevents TNT from tunnelling through floors, walls, or any
                // solid/partial block while we are overriding vanilla physics via
                // teleportation.  sweepPath stops at the last safe position before
                // the first blocking surface.
                double[] swept = sweepPath(tnt.getWorld(),
                        lp[0], lp[1], lp[2], destX, destY, destZ);
                double nextX = swept[0];
                double nextY = swept[1];
                double nextZ = swept[2];

                // If any axis was stopped by a block, zero the corresponding
                // velocity component so the TNT does not keep pushing into the block.
                if (Math.abs(nextX - destX) > 1e-4) ax = 0;
                if (Math.abs(nextY - destY) > 1e-4) ay = 0;
                if (Math.abs(nextZ - destZ) > 1e-4) az = 0;

                // Teleport entity forward to the swept (collision-safe) position.
                Location curLoc = tnt.getLocation();
                tnt.teleport(new Location(tnt.getWorld(), nextX, nextY, nextZ,
                        curLoc.getYaw(), curLoc.getPitch()));

                launchedPos.put(uuid, new double[]{nextX, nextY, nextZ});
                prevVel.put(uuid, new Vector(ax, ay, az));

                if (ax * ax + az * az < LAUNCH_CLEAR_SPEED_SQ) {
                    launchedTNT.remove(uuid);
                    launchedPos.remove(uuid);
                }

                // Zero velocity - we own the position; vanilla must not move the entity.
                tnt.setVelocity(new Vector(0, 0, 0));
                return;

            } else {
                // In air - clear water tracking, apply gravity + partial collision.
                launchedPos.remove(uuid);

                nx = actualVel.getX();
                ny = actualVel.getY();
                nz = actualVel.getZ();

                if (partialBlockEnabled) {
                    Vector resolved = resolvePartialCollision(tnt, new Vector(nx, ny, nz));
                    nx = resolved.getX();
                    ny = resolved.getY();
                    nz = resolved.getZ();
                }
            }

        } else {
            // Not explosion-launched: stationary or slow-moving TNT.

            if (inWater) {
                // STATIC PIN in water.
                //
                // noPhysics=true (set above) is the PREFERRED mechanism: it makes
                // vanilla Entity.move() a no-op so buoyancy, water-current, and gravity
                // produce zero displacement.  However, if NMS reflection fails silently
                // in this Paper build (different field name, module access denied, etc.),
                // noPhysics would be a no-op and water current would push the TNT on
                // every tick — causing the "drifting" and apparent "behind the dispenser"
                // behaviour users see.
                //
                // The unconditional teleport below is the belt-and-suspenders backup:
                // regardless of whether noPhysics worked, we forcibly return the entity
                // to its anchor every tick.  The maximum visible drift is one vanilla tick
                // of water-current motion (~0.01 block) — well below the 0.0625-block
                // client-update threshold, so players see the TNT as perfectly stationary.

                Location curLoc = tnt.getLocation();
                double[] saved = pinnedPos.get(uuid);
                if (saved == null) {
                    // First tick seeing this TNT in water — record current position as anchor.
                    saved = new double[]{curLoc.getX(), curLoc.getY(), curLoc.getZ()};
                    pinnedPos.put(uuid, saved);
                } else {
                    // Every subsequent tick: unconditionally snap back to the anchor.
                    tnt.teleport(new Location(tnt.getWorld(),
                            saved[0], saved[1], saved[2],
                            curLoc.getYaw(), curLoc.getPitch()));
                }

                nx = 0;
                ny = 0;
                nz = 0;
            } else {
                // In AIR, not explosion-launched: pass vanilla's computed velocity
                // through (preserves prime momentum and gravity) then apply partial-
                // block collision on top.  Do NOT zero X/Z — that would kill the
                // initial prime-momentum jump every cannon cannon designer relies on.
                nx = actualVel.getX();
                ny = actualVel.getY();
                nz = actualVel.getZ();

                if (partialBlockEnabled) {
                    Vector resolved = resolvePartialCollision(tnt, new Vector(nx, ny, nz));
                    nx = resolved.getX();
                    ny = resolved.getY();
                    nz = resolved.getZ();
                }
            }
        }

        Vector newVel = new Vector(nx, ny, nz);

        if (nx * nx + nz * nz < LAUNCH_CLEAR_SPEED_SQ) {
            launchedTNT.remove(uuid);
            launchedPos.remove(uuid);
        }

        prevVel.put(uuid, newVel);
        tnt.setVelocity(newVel);
    }

    /** Returns true if there is a solid block directly beneath the TNT entity. */
    private boolean isSolidBelow(TNTPrimed tnt) {
        Block below = tnt.getLocation().subtract(0, 0.1, 0).getBlock();
        return below.getType().isSolid();
    }

    /**
     * Sweeps from (sx, sy, sz) to (ex, ey, ez) in fine steps, checking at each
     * point whether a TNT-sized AABB intersects a solid or partial block.
     *
     * Returns the last clear sample point before the first collision;
     * if no obstacles are found, returns {ex, ey, ez}.
     *
     * Step resolution: â‰¥8 steps per block of distance so TNT travelling at up
     * to 3 b/t is sampled at â‰¤0.375 b/step â€” a single slab or fence cannot be
     * tunnelled through.
     */
    private double[] sweepPath(World world,
                               double sx, double sy, double sz,
                               double ex, double ey, double ez) {
        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1e-8) return new double[]{ex, ey, ez};

        double dist = Math.sqrt(distSq);
        int steps = Math.max(4, (int) Math.ceil(dist * 8)); // â‰¥8 checks per block
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        double lastX = sx, lastY = sy, lastZ = sz;
        for (int i = 1; i <= steps; i++) {
            double cx = sx + stepX * i;
            double cy = sy + stepY * i;
            double cz = sz + stepZ * i;
            if (isPositionBlocked(world, cx, cy, cz)) {
                return new double[]{lastX, lastY, lastZ};
            }
            lastX = cx;
            lastY = cy;
            lastZ = cz;
        }
        return new double[]{ex, ey, ez};
    }

    /**
     * Returns true if a TNT-sized AABB (0.98 Ã— 0.98 Ã— 0.98) centred at
     * (cx, cy, cz) overlaps any full solid block or any partial block (slab,
     * fence, wall, trapdoor, etc.) whose actual bounding box intersects the AABB.
     *
     * Water blocks are NOT solid so they are transparently passed through,
     * which is correct â€” cannon pots are water-filled.
     */
    private boolean isPositionBlocked(World world, double cx, double cy, double cz) {
        final double HALF = 0.49;
        BoundingBox tntBB = new BoundingBox(
                cx - HALF, cy - HALF, cz - HALF,
                cx + HALF, cy + HALF, cz + HALF);
        int minX = (int) Math.floor(cx - HALF);
        int maxX = (int) Math.floor(cx + HALF);
        int minY = (int) Math.floor(cy - HALF);
        int maxY = (int) Math.floor(cy + HALF);
        int minZ = (int) Math.floor(cz - HALF);
        int maxZ = (int) Math.floor(cz + HALF);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    Material m = block.getType();
                    if (m.isAir() || m == Material.WATER || m == Material.LAVA) continue;
                    if (PARTIAL_MATERIALS.contains(m)) {
                        BoundingBox blockBB = block.getBoundingBox();
                        if (blockBB.getVolume() > 1e-6 && tntBB.overlaps(blockBB)) return true;
                    } else if (m.isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInWater(Block block) {
        if (block.getType() == Material.WATER) return true;
        if (block.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl) {
            return wl.isWaterlogged();
        }
        return false;
    }

    /**
     * Returns true if any block overlapped by the TNT entity's bounding box
     * (expanded by 0.4 blocks on all sides) contains water.
     * The expansion ensures TNT exploding at a dry ladder block immediately
     * adjacent to water still triggers the water-protection check.
     */
    /**
     * Returns true if the TNT entity's actual bounding box overlaps a water or
     * waterlogged block.  No margin — used for per-tick physics decisions so that
     * TNT in air above a water surface is NOT treated as in-water.
     */
    private boolean isTNTInWater(TNTPrimed tnt) {
        BoundingBox bb = tnt.getBoundingBox();
        int minX = (int) Math.floor(bb.getMinX());
        int maxX = (int) Math.floor(bb.getMaxX());
        int minY = (int) Math.floor(bb.getMinY());
        int maxY = (int) Math.floor(bb.getMaxY());
        int minZ = (int) Math.floor(bb.getMinZ());
        int maxZ = (int) Math.floor(bb.getMaxZ());
        World world = tnt.getWorld();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (isInWater(world.getBlockAt(bx, by, bz))) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if any block within {@code margin} blocks of the TNT entity's
     * bounding box contains water.  Used only for explosion-event water checks
     * where a generous margin is needed to catch edge cases.
     */
    private boolean isTNTTouchingWater(TNTPrimed tnt) {
        final double MARGIN = 0.4;
        BoundingBox bb = tnt.getBoundingBox();
        int minX = (int) Math.floor(bb.getMinX() - MARGIN);
        int maxX = (int) Math.floor(bb.getMaxX() + MARGIN);
        int minY = (int) Math.floor(bb.getMinY() - MARGIN);
        int maxY = (int) Math.floor(bb.getMaxY() + MARGIN);
        int minZ = (int) Math.floor(bb.getMinZ() - MARGIN);
        int maxZ = (int) Math.floor(bb.getMaxZ() + MARGIN);
        World world = tnt.getWorld();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (isInWater(world.getBlockAt(bx, by, bz))) return true;
                }
            }
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
                        // to their attachment wall.  Y is never blocked â€” TNT falling down past
                        // (or onto) a ladder must not be caught by it from above.
                        // getFacing() returns the direction AWAY from the wall (into the room):
                        //   EAST/WEST facing  â†’ attached to west/east  wall â†’ blocks X axis
                        //   NORTH/SOUTH facing â†’ attached to south/north wall â†’ blocks Z axis
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
                        continue; // never check Y â€” ladders cannot catch TNT from above
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
     * fires â€” entity damage and velocity applied to nearby TNT are unaffected â€” but
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

        UUID eid = tnt.getUniqueId();

        // â”€â”€ Water check BEFORE cleanup â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // For launched TNT we teleport the entity to a clean air-trajectory
        // position each tick, so the entity's current bounding box is NOT in
        // water even when the true detonation point is.  We therefore check
        // water using several sources in order of reliability:
        //
        //  1. launchedPos entry still present â†’ TNT was travelling through water
        //     on this tick (launchedPos is cleared when TNT leaves water to air).
        //  2. pinnedPos entry still present   â†’ TNT was pinned in a water pot.
        //  3. Bounding-box + margin check on the entity's current position
        //     (covers normal, non-teleported TNT).
        //  4. Radius check around the event location (fallback for edge cases).

        boolean wasInWater = false;

        double[] lp = launchedPos.get(eid);
        if (lp != null) {
            // The saved launch position IS the real water position.
            // Check water at that location with margin.
            wasInWater = isLocationNearWater(tnt.getWorld(), lp[0], lp[1], lp[2], 0.5);
        }

        if (!wasInWater && pinnedPos.containsKey(eid)) {
            wasInWater = true; // pinned in water pot â€” always suppressed
        }

        if (!wasInWater) {
            wasInWater = isTNTTouchingWater(tnt);
        }

        if (!wasInWater) {
            Location eloc = event.getLocation();
            wasInWater = isLocationNearWater(eloc.getWorld(), eloc.getX(), eloc.getY(), eloc.getZ(), 0.5);
        }

        // Now do cleanup
        trackedTNT.remove(eid);
        prevVel.remove(eid);
        launchedTNT.remove(eid);
        pinnedPos.remove(eid);
        launchedPos.remove(eid);

        if (wasInWater) {
            event.blockList().clear();
        }
    }

    /**
     * Returns true if any block within {@code radius} blocks of (x, y, z) is water.
     */
    private boolean isLocationNearWater(World world, double x, double y, double z, double radius) {
        int minX = (int) Math.floor(x - radius);
        int maxX = (int) Math.floor(x + radius);
        int minY = (int) Math.floor(y - radius);
        int maxY = (int) Math.floor(y + radius);
        int minZ = (int) Math.floor(z - radius);
        int maxZ = (int) Math.floor(z + radius);
        for (int bx = minX; bx <= maxX; bx++)
            for (int by = minY; by <= maxY; by++)
                for (int bz = minZ; bz <= maxZ; bz++)
                    if (isInWater(world.getBlockAt(bx, by, bz))) return true;
        return false;
    }


    // -----------------------------------------------------------------------
    // Explosion exposure adjustment
    // -----------------------------------------------------------------------

    /**
     * Computes partial-block exposure attenuation SYNCHRONOUSLY at MONITOR priority.
     * At this point vanilla has already applied blast velocity to all nearby entities,
     * so tnt.getVelocity() == post-blast; prevVel map == pre-blast baseline.
     * delta = post - pre = raw blast impulse.  We attenuate per-axis by how much
     * of the ray explosion -> TNT passes through partial blocks (ladder, slab, etc).
     *
     * WHY NOT DELAYED: for water-pinned TNT, tickAllTNT runs first next tick and
     * zeros velocity (it owns the trajectory).  A 1-tick-later callback would read
     * postVel=0, delta=0, and silently do nothing -- breaking exposure for every TNT
     * sitting in a cannon water pot.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!exposureEnabled) return;
        if (!(event.getEntity() instanceof TNTPrimed)) return;

        Location explosionLoc = event.getLocation();

        for (Entity e : explosionLoc.getNearbyEntities(
                BLAST_SAMPLE_RADIUS, BLAST_SAMPLE_RADIUS, BLAST_SAMPLE_RADIUS)) {
            if (!(e instanceof TNTPrimed tnt) || !tnt.isValid()) continue;
            if (tnt == event.getEntity()) continue; // the detonating TNT itself

            UUID tid       = tnt.getUniqueId();
            Vector preVel  = prevVel.getOrDefault(tid, new Vector(0, 0, 0)); // pre-blast
            Vector postVel = tnt.getVelocity();                               // post-blast
            Vector delta   = postVel.clone().subtract(preVel);

            // 1e-6 catches blast impulses as small as ~0.001 b/t (e.g. far-range
            // explosions or near-zero exposure through ladders) without matching the
            // near-zero noise from per-tick velocity bookkeeping.
            if (delta.lengthSquared() < 1e-6) continue;

            Vector axisCov = rayMarchAxisCoverage(
                    explosionLoc,
                    tnt.getLocation().add(0, 0.49, 0));

            double expX = 1.0 - axisCov.getX();
            double expY = 1.0 - axisCov.getY();
            double expZ = 1.0 - axisCov.getZ();

            // Fully exposed on all axes -- leave vanilla blast result unchanged
            if (expX >= 1.0 - 1e-3 && expY >= 1.0 - 1e-3 && expZ >= 1.0 - 1e-3) continue;

            Vector newVel = preVel.clone().add(new Vector(
                    delta.getX() * expX,
                    delta.getY() * expY,
                    delta.getZ() * expZ));

            tnt.setVelocity(newVel);
            // Update prevVel and mark as launched so tickAllTNT on the next tick
            // treats this as explosion-pushed rather than pinning it to zero.
            syncVelocityFromExternal(tid, newVel);
        }
    }

    /**
     * Updates the velocity baseline ({@code prevVel}) for a TNT entity pushed by
     * an explosion handled outside of {@link #tickAllTNT}.  Also marks the entity
     * as explosion-launched so the next physics tick treats its current speed as
     * intentional rather than an anomaly to zero out.
     *
     * @param id     UUID of the TNT entity
     * @param newVel velocity just applied via {@link org.bukkit.entity.Entity#setVelocity}
     */
    private void syncVelocityFromExternal(UUID id, Vector newVel) {
        prevVel.put(id, newVel.clone());
        // Always mark as launched — this is only called when an explosion has pushed
        // this TNT. We must unconditionally clear pinnedPos so the water-pin teleport
        // does not snap the entity back to its original position mid-flight.
        launchedTNT.add(id);
        pinnedPos.remove(id);
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

        // Exclude only the last 0.3 blocks so a stopper block coincident with the
        // target position does not penalise it, but ladders immediately adjacent to
        // the target (which is the common cannon stopper geometry) ARE sampled.
        // The old 1.0-block exclusion was too aggressive: it caused ladders right
        // next to target TNT to be completely skipped, breaking ladder exposure.
        double sampleLength = Math.max(0.0, length - 0.3);
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
