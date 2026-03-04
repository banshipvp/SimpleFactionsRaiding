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

        // If the target block is solid (e.g. glass separator between cannon pots)
        // we must find a passable block to spawn the TNT in.
        //
        // Two-strategy search:
        //  1. Scan further in the FACING direction — handles horizontal dispensers
        //     where the glass is directly in front and the water pot is at the same Y.
        //  2. Scan DOWNWARD from the target block — handles designs where the glass
        //     separator sits at the dispenser's level and the water pot is one block
        //     below (common in vertical top-down cannons).
        Block spawnBlock = null;
        if (targetBlock.isPassable()) {
            spawnBlock = targetBlock;
        } else {
            // Strategy 1: continue in the facing direction past the solid block.
            Block next = targetBlock.getRelative(facing);
            for (int i = 0; i < 4; i++) {
                if (next.isPassable()) {
                    spawnBlock = next;
                    break;
                }
                next = next.getRelative(facing);
            }
            // Strategy 2 (fallback): scan downward from the solid target block.
            // This covers layouts where the water pot is below the glass separator
            // that the dispenser is aimed at.
            if (spawnBlock == null) {
                Block below = targetBlock.getRelative(BlockFace.DOWN);
                for (int i = 0; i < 4; i++) {
                    if (below.isPassable()) {
                        spawnBlock = below;
                        break;
                    }
                    below = below.getRelative(BlockFace.DOWN);
                }
            }
            // Final fallback: use the target block itself — the entity will
            // be inside a solid block, but vanilla's push-out will move it free.
            if (spawnBlock == null) {
                spawnBlock = targetBlock;
            }
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
