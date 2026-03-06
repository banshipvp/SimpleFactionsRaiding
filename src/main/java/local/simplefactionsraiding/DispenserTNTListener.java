package local.simplefactionsraiding;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixes TNT dispenser physics so that TNT entities spawn exactly centered
 * inside the block they land in, with zero initial velocity. This prevents
 * the vanilla off-center spawn position from causing the TNT hitbox to
 * clip into adjacent blocks (a common cannoning bug).
 *
 * Also prevents the dispenser from firing more than once per game tick on a
 * single redstone pulse.  Cancelling BlockDispenseEvent does not update the
 * dispenser's internal "has fired" state, so update-order chaining in the
 * same tick can re-trigger the event.  We suppress re-fires only within the
 * SAME server tick (using Bukkit.getCurrentTick()), which means a dispenser
 * that receives a legitimately new redstone HIGH signal one or more ticks
 * later fires correctly — this is essential for multi-pulse cannon sequences.
 */
public class DispenserTNTListener implements Listener {

    /** One redstone tick in game ticks. */
    private static final int OBSERVER_PULSE_TICKS = 2;

    /**
     * TNT entity location uses feet Y. A +0.01 offset centres the 0.98-tall
     * hitbox inside a block and avoids clipping into floor/ceiling boundaries.
     */
    private static final double TNT_FEET_CENTER_OFFSET = 0.01;

    private final JavaPlugin plugin;
    private final boolean enabled;

    /**
     * Maps dispenser location key → server game tick on which it last fired.
     * Cleared on falling redstone edge.  We suppress re-fires only within the
     * same tick so that intentional multi-delay sequences work correctly.
     */
    private final Map<String, Integer> lastFiredTick = new HashMap<>();

    public DispenserTNTListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("cannoning.fix-dispenser-tnt-physics", true);
    }

    /**
     * Clears the per-dispenser tick record when the redstone signal drops to zero.
     * This ensures the next rising edge is always treated as a fresh activation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        String key = blockKey(event.getBlock());
        if (event.getNewCurrent() == 0) {
            // Falling edge: clear state so next front update/rising edge can fire.
            lastFiredTick.remove(key);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseTNT(BlockDispenseEvent event) {
        if (!enabled) return;
        if (event.getItem().getType() != Material.TNT) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Dispenser)) return;

        String key = blockKey(block);

        // Suppress duplicate fires that happen in the SAME server tick.
        // Using game tick (not wall-clock) is critical: a cannon may intentionally
        // activate the same dispenser on two consecutive ticks (50 ms each), which
        // a wall-clock cooldown of >50 ms would incorrectly suppress.
        int currentTick = plugin.getServer().getCurrentTick();
        Integer firedTick = lastFiredTick.get(key);
        if (firedTick != null && (currentTick - firedTick) < OBSERVER_PULSE_TICKS) {
            event.setCancelled(true);
            return;
        }

        Dispenser dispenserState = (Dispenser) block.getState();
        BlockFace facing = getFacing(block);
        if (facing == null) return;

        // Manually consume one TNT from the dispenser inventory
        consumeOneTNT(dispenserState);

        // Determine the block directly in front of the dispenser.
        Block targetBlock = block.getRelative(facing);

        // Search for the best block to spawn TNT in, prioritising cannon water pots.
        //
        // Cannon water pots are always water or waterlogged; spawning in any OTHER
        // passable block (e.g. an air gap outside the cannon wall) would place the
        // TNT outside the structure.  We therefore search specifically for water
        // rather than generic "passable" blocks.
        //
        // Search order:
        //  1. Target block itself contains water → exact match, use it.
        //  2. Scan up to 8 blocks in the FACING direction for water → handles
        //     thick glass separators between dispenser and water pot.
        //  3. Scan up to 4 blocks DOWNWARD from the target block for water →
        //     handles vertical top-down cannon designs where the pot is below.
        //  4. Nearest passable block in facing direction (up to 8) → open-air
        //     cannon designs with no water.
        //  5. Target block itself (solid) → entity lands in glass; vanilla
        //     push-out will resolve it as a last resort.
        Block spawnBlock = null;

        // Strategy 1: target block already has water.
        if (isWaterBlock(targetBlock)) {
            spawnBlock = targetBlock;
        }

        // Strategy 2: scan facing direction for a water block.
        // Scan through ANYTHING (glass, air gaps, etc.) — cannon internals
        // often have air gaps between the glass separator and the water pot.
        if (spawnBlock == null) {
            Block next = targetBlock.getRelative(facing);
            for (int i = 0; i < 8; i++) {
                if (isWaterBlock(next)) {
                    spawnBlock = next;
                    break;
                }
                next = next.getRelative(facing);
            }
        }

        // Strategy 3: scan downward from targetBlock for a water block.
        if (spawnBlock == null) {
            Block below = targetBlock.getRelative(BlockFace.DOWN);
            for (int i = 0; i < 4; i++) {
                if (isWaterBlock(below)) {
                    spawnBlock = below;
                    break;
                }
                below = below.getRelative(BlockFace.DOWN);
            }
        }

        // Strategy 4: direct-front passable fallback only.
        // Never scan ahead for arbitrary passable blocks; that can spawn TNT
        // outside cannon geometry when odd redstone/observer updates fire.
        if (spawnBlock == null) {
            if (targetBlock.isPassable()) {
                spawnBlock = targetBlock;
            }
        }

        // Strategy 5: if no safe custom location is found, let vanilla handle it.
        if (spawnBlock == null) {
            return;
        }

        // If the chosen block is still solid and non-water, hand off to vanilla.
        if (spawnBlock.getType().isSolid() && !isWaterBlock(spawnBlock)) {
            return;
        }

        // Cancel the vanilla dispense so we control the spawn
        event.setCancelled(true);

        // Record the tick this dispenser fired so duplicate re-triggers are suppressed.
        lastFiredTick.put(key, currentTick);

        // Spawn TNT at the VERTICAL CENTRE of the chosen block (Y+0.5).
        // Spawning at the floor (Y+0.0) puts the entity right at the block
        // boundary — one tick of vanilla gravity moves it to Y-0.04 which
        // floor()-maps to the block below, making isTNTInWater return false
        // and allowing the pin to lapse.  Y+0.5 provides 0.5-block margin.
        int fuseTicks = plugin.getConfig().getInt("cannoning.tnt-fuse-ticks", 80);

        final Block finalSpawnBlock = spawnBlock;
        finalSpawnBlock.getWorld().spawn(
            finalSpawnBlock.getLocation().add(0.5, TNT_FEET_CENTER_OFFSET, 0.5),
                TNTPrimed.class,
                tnt -> {
                    tnt.setVelocity(new Vector(0, 0, 0));
                    tnt.setFuseTicks(fuseTicks);
                    tnt.setSilent(true); // suppress hissing packet spam with many TNT entities
                }
        );
    }

    /**
     * Returns true if the block contains water (source, flowing, or waterlogged).
     * Cannon water pots may use waterlogged blocks (e.g. waterlogged slabs or
     * trapdoors) which are solid but still count as water for physics purposes.
     */
    private static boolean isWaterBlock(Block block) {
        if (block.getType() == Material.WATER) return true;
        org.bukkit.block.data.BlockData data = block.getBlockData();
        if (data instanceof org.bukkit.block.data.Waterlogged wl) {
            return wl.isWaterlogged();
        }
        return false;
    }

    private static String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    /**
     * Removes one TNT item from the dispenser's inventory.
     */
    private void consumeOneTNT(Dispenser dispenserState) {
        ItemStack[] contents = dispenserState.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == Material.TNT) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    contents[i] = null;
                }
                dispenserState.getInventory().setContents(contents);
                return;
            }
        }
    }

    /**
     * Reads the BlockFace the dispenser is facing from its block data.
     */
    private BlockFace getFacing(Block block) {
        try {
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.Directional) {
                return ((org.bukkit.block.data.Directional) data).getFacing();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("DispenserTNTListener: failed to read dispenser facing - " + e.getMessage());
        }
        return null;
    }
}
