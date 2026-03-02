package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Premium cannoning consistency and physics features, inspired by AtlasSpigot:
 *
 * 1.  ANTI-NUDGE               - TNT placed in a scoreboard COLLISION_RULE=NEVER team.
 * 2.  CONSTANT EXPLOSION YIELD - Fixes radius via ExplosionPrimeEvent.
 * 3.  TNT VELOCITY CAP         - Clamps TNT speed every tick.
 * 4.  SAND STASIS NORMALISE    - Resets falling-block Y velocity to -0.04.
 * 5.  EXPLOSION WATER CHECK    - TNT inside liquid destroys no blocks.
 * 6.  EXPLODE WATERLOGGED      - Explosion breaks waterlogged blocks (off by default).
 * 7.  EXPLODE LAVA             - Explosion breaks lava blocks (off by default).
 * 8.  ENTITY MERGING           - TNT/FallingBlock at same position+velocity+fuse merged.
 * 9.  CHUNK LOADING            - Chunks with active TNT/FallingBlock kept loaded.
 * 10. TNT SPREAD TOGGLE        - Zeroes horizontal velocity on TNT spawn when disabled.
 */
public class CannonConsistencyListener implements Listener {

    private static final String TEAM_NAME = "sfr_no_collide";
    private static final double VANILLA_TNT_YIELD = 4.0;

    private final JavaPlugin plugin;

    // Feature flags & parameters
    private final boolean antiNudgeEnabled;
    private final boolean constantYieldEnabled;
    private final float   constantYield;
    private final boolean velocityCapEnabled;
    private final double  velocityCapSq;
    private final double  velocityCap;
    private final boolean sandStasisEnabled;
    private final boolean explosionWaterCheckEnabled;
    private final boolean explodeWaterloggedEnabled;
    private final boolean explodeLavaEnabled;
    private final boolean entityMergeEnabled;
    private final boolean chunkLoadingEnabled;
    private final boolean tntSpreadEnabled; // true = vanilla spread, false = no spread

    private Team noCollideTeam;

    public CannonConsistencyListener(JavaPlugin plugin) {
        this.plugin = plugin;

        antiNudgeEnabled           = plugin.getConfig().getBoolean("cannoning.anti-nudge",               true);
        constantYieldEnabled       = plugin.getConfig().getBoolean("cannoning.constant-explosion-yield", true);
        constantYield              = (float) plugin.getConfig().getDouble("cannoning.explosion-yield",    VANILLA_TNT_YIELD);
        velocityCapEnabled         = plugin.getConfig().getBoolean("cannoning.velocity-cap",             true);
        double cap                 = plugin.getConfig().getDouble("cannoning.max-velocity",              3.0);
        velocityCap                = cap;
        velocityCapSq              = cap * cap;
        sandStasisEnabled          = plugin.getConfig().getBoolean("cannoning.sand-stasis-normalise",    true);
        explosionWaterCheckEnabled = plugin.getConfig().getBoolean("cannoning.explosion-water-check",    true);
        explodeWaterloggedEnabled  = plugin.getConfig().getBoolean("cannoning.explode-waterlogged",      false);
        explodeLavaEnabled         = plugin.getConfig().getBoolean("cannoning.explode-lava",             false);
        entityMergeEnabled         = plugin.getConfig().getBoolean("cannoning.entity-merge",             true);
        chunkLoadingEnabled        = plugin.getConfig().getBoolean("cannoning.chunk-loading",            true);
        tntSpreadEnabled           = plugin.getConfig().getBoolean("cannoning.tnt-spread",               false);

        if (antiNudgeEnabled) {
            setupNoCollideTeam();
        }

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::onTick, 1L, 1L);
    }

    // =========================================================================
    // Scoreboard team (anti-nudge)
    // =========================================================================

    private void setupNoCollideTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team existing = sb.getTeam(TEAM_NAME);
        if (existing != null) existing.unregister();
        noCollideTeam = sb.registerNewTeam(TEAM_NAME);
        noCollideTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
    }

    // =========================================================================
    // Per-tick scheduler
    // =========================================================================

    private void onTick() {
        for (var world : Bukkit.getWorlds()) {
            if (velocityCapEnabled) tickVelocityCap(world);
            if (entityMergeEnabled)  tickEntityMerge(world);
        }
    }

    private void tickVelocityCap(org.bukkit.World world) {
        for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
            if (!tnt.isValid()) continue;
            Vector vel = tnt.getVelocity();
            if (vel.lengthSquared() > velocityCapSq) {
                tnt.setVelocity(vel.normalize().multiply(velocityCap));
            }
        }
    }

    /**
     * Entity merging (AtlasSpigot 0848):
     * Two TNT/FallingBlock at the same position with the same velocity and fuse
     * are the same cannon shot. Remove extras, keeping one representative.
     * Position rounded to 3 dp (1 mm) to absorb floating-point noise.
     */
    private void tickEntityMerge(org.bukkit.World world) {
        // TNT merging
        Map<String, TNTPrimed> seenTnt = new LinkedHashMap<>();
        List<TNTPrimed> removeTnt = new ArrayList<>();

        for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
            if (!tnt.isValid()) continue;
            String key = buildTntKey(tnt);
            if (seenTnt.containsKey(key)) {
                removeTnt.add(tnt);
            } else {
                seenTnt.put(key, tnt);
            }
        }
        removeTnt.forEach(org.bukkit.entity.Entity::remove);

        // FallingBlock merging
        Map<String, FallingBlock> seenFb = new LinkedHashMap<>();
        List<FallingBlock> removeFb = new ArrayList<>();

        for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
            if (!fb.isValid()) continue;
            String key = buildFbKey(fb);
            if (seenFb.containsKey(key)) {
                removeFb.add(fb);
            } else {
                seenFb.put(key, fb);
            }
        }
        removeFb.forEach(org.bukkit.entity.Entity::remove);
    }

    private static String buildTntKey(TNTPrimed tnt) {
        return roundKey(tnt.getLocation().getX(), tnt.getLocation().getY(), tnt.getLocation().getZ(),
                tnt.getVelocity().getX(), tnt.getVelocity().getY(), tnt.getVelocity().getZ())
                + ":" + tnt.getFuseTicks();
    }

    private static String buildFbKey(FallingBlock fb) {
        return roundKey(fb.getLocation().getX(), fb.getLocation().getY(), fb.getLocation().getZ(),
                fb.getVelocity().getX(), fb.getVelocity().getY(), fb.getVelocity().getZ())
                + ":" + fb.getBlockData().getMaterial().name();
    }

    private static String roundKey(double x, double y, double z, double vx, double vy, double vz) {
        return round3(x) + "," + round3(y) + "," + round3(z)
                + ":" + round3(vx) + "," + round3(vy) + "," + round3(vz);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // =========================================================================
    // 1. Anti-nudge + chunk loading + spread toggle (TNT spawn)
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTNTSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        if (antiNudgeEnabled && noCollideTeam != null) {
            noCollideTeam.addEntity(tnt);
        }

        // TNT spread toggle: disable lateral scatter (AtlasSpigot 0855)
        if (!tntSpreadEnabled) {
            Vector v = tnt.getVelocity();
            tnt.setVelocity(new Vector(0, v.getY(), 0));
        }

        if (chunkLoadingEnabled) {
            tnt.getLocation().getChunk().load(false);
        }
    }

    // =========================================================================
    // 4. Sand stasis normalisation + chunk loading (FallingBlock spawn)
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fb)) return;

        if (chunkLoadingEnabled) {
            fb.getLocation().getChunk().load(false);
        }

        if (!sandStasisEnabled) return;

        String matName = fb.getBlockData().getMaterial().name();
        boolean isFallable = matName.equals("SAND")
                || matName.equals("RED_SAND")
                || matName.equals("GRAVEL")
                || matName.equals("ANVIL")
                || matName.equals("CHIPPED_ANVIL")
                || matName.equals("DAMAGED_ANVIL")
                || matName.endsWith("_CONCRETE_POWDER");

        if (!isFallable) return;

        Vector v = fb.getVelocity();
        boolean needsFix = Math.abs(v.getY() + 0.04) > 0.01
                || Math.abs(v.getX()) > 0.01
                || Math.abs(v.getZ()) > 0.01;
        if (needsFix) {
            fb.setVelocity(new Vector(0, -0.04, 0));
        }
    }

    // =========================================================================
    // 2. Constant explosion yield
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!constantYieldEnabled) return;
        if (!(event.getEntity() instanceof TNTPrimed)) return;
        event.setRadius(constantYield);
        event.setFire(false);
    }

    // =========================================================================
    // 5-7. Explosion: water check, waterlogged blocks, lava blocks
    // =========================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed)) return;

        org.bukkit.Location loc = event.getLocation();
        Block originBlock = loc.getBlock();

        // 5. Explosion water check (AtlasSpigot 0847):
        //    If TNT detonates inside liquid, no block damage.
        //    Exception: if explode-lava is on and origin is lava, allow it.
        if (explosionWaterCheckEnabled) {
            Material mat = originBlock.getType();
            boolean inLiquid = mat == Material.WATER
                    || mat == Material.LAVA
                    || mat == Material.BUBBLE_COLUMN;
            boolean lavaExempt = explodeLavaEnabled && mat == Material.LAVA;

            if (inLiquid && !lavaExempt) {
                event.blockList().clear();
                return;
            }
        }

        List<Block> blocks = event.blockList();
        double radius = event.getYield();
        int ri = (int) Math.ceil(radius);
        org.bukkit.World world = loc.getWorld();
        if (world == null) return;

        // 6. Explode waterlogged blocks (AtlasSpigot 0854)
        if (explodeWaterloggedEnabled) {
            for (int x = -ri; x <= ri; x++) {
                for (int y = -ri; y <= ri; y++) {
                    for (int z = -ri; z <= ri; z++) {
                        if ((double)(x*x + y*y + z*z) > radius * radius) continue;
                        Block b = world.getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z);
                        if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()
                                && !blocks.contains(b)) {
                            blocks.add(b);
                        }
                    }
                }
            }
        }

        // 7. Explode lava (AtlasSpigot 0854)
        if (explodeLavaEnabled) {
            for (int x = -ri; x <= ri; x++) {
                for (int y = -ri; y <= ri; y++) {
                    for (int z = -ri; z <= ri; z++) {
                        if ((double)(x*x + y*y + z*z) > radius * radius) continue;
                        Block b = world.getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z);
                        if (b.getType() == Material.LAVA && !blocks.contains(b)) {
                            blocks.add(b);
                        }
                    }
                }
            }
        }
    }
}
