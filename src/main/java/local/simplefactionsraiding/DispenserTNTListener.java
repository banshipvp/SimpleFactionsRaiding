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
        if (event.getNewCurrent() == 0) {
            lastFiredTick.remove(blockKey(event.getBlock()));
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
        if (firedTick != null && firedTick == currentTick) {
            event.setCancelled(true);
            return;
        }

        Dispenser dispenserState = (Dispenser) block.getState();
        BlockFace facing = getFacing(block);
        if (facing == null) return;

        // Cancel the vanilla dispense so we control the spawn
        event.setCancelled(true);

        // Record the tick this dispenser fired so same-tick re-triggers are suppressed.
        lastFiredTick.put(key, currentTick);

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

        // Strategy 2: scan facing direction for a water block (skip solid sepa­rators).
        if (spawnBlock == null) {
            Block next = targetBlock.getRelative(facing);
            for (int i = 0; i < 8; i++) {
                if (isWaterBlock(next)) {
                    spawnBlock = next;
                    break;
                }
                // Stop scanning when we leave solid material — another patch of
                // solid likely means we have left the cannon body entirely.
                if (next.isPassable() && !isWaterBlock(next)) {
                    break; // hit open air beyond the wall — do not go further
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

        // Strategy 4: nearest passable (non-water) block in facing direction
        // — fallback for open-air cannon designs.
        if (spawnBlock == null) {
            Block next = targetBlock;
            for (int i = 0; i < 8; i++) {
                if (next.isPassable()) {
                    spawnBlock = next;
                    break;
                }
                next = next.getRelative(facing);
            }
        }

        // Strategy 5: last resort — use target block (push-out will handle solids).
        if (spawnBlock == null) {
            spawnBlock = targetBlock;
        }

        // Spawn TNT exactly at the center of the chosen block with zero velocity.
        // Zero velocity prevents the hitbox from crossing into any adjacent block
        // on the first tick. Water flow or other mechanics will apply forces after.
        int fuseTicks = plugin.getConfig().getInt("cannoning.tnt-fuse-ticks", 80);

        final Block finalSpawnBlock = spawnBlock;
        finalSpawnBlock.getWorld().spawn(
                finalSpawnBlock.getLocation().add(0.5, 0.0, 0.5),
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
