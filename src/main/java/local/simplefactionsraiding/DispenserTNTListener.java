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
 * Also prevents the dispenser from firing more than once per rising redstone
 * edge. Cancelling BlockDispenseEvent does not update the dispenser's internal
 * "has fired" state, so subsequent block updates in the same pulse can re-trigger
 * the event. A wall-clock cooldown (immune to TPS lag) fixes this.
 */
public class DispenserTNTListener implements Listener {

    // 150 ms window — covers all same-pulse re-triggers regardless of TPS
    private static final long COOLDOWN_MS = 150L;

    private final JavaPlugin plugin;
    private final boolean enabled;

    /**
     * Maps dispenser location key → System.currentTimeMillis() of last fire.
     * Cleared on falling redstone edge or after COOLDOWN_MS has elapsed.
     */
    private final Map<String, Long> lastFiredAt = new HashMap<>();

    public DispenserTNTListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("cannoning.fix-dispenser-tnt-physics", true);
    }

    /**
     * Clears the per-dispenser cooldown when the redstone signal drops to zero.
     * This ensures the next rising edge is always treated as a fresh activation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (event.getNewCurrent() == 0) {
            lastFiredAt.remove(blockKey(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseTNT(BlockDispenseEvent event) {
        if (!enabled) return;
        if (event.getItem().getType() != Material.TNT) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Dispenser)) return;

        String key = blockKey(block);

        // If this dispenser fired recently (within COOLDOWN_MS wall-clock time), suppress.
        long now = System.currentTimeMillis();
        Long lastTime = lastFiredAt.get(key);
        if (lastTime != null && (now - lastTime) < COOLDOWN_MS) {
            event.setCancelled(true);
            return;
        }

        Dispenser dispenserState = (Dispenser) block.getState();
        BlockFace facing = getFacing(block);
        if (facing == null) return;

        // Cancel the vanilla dispense so we control the spawn
        event.setCancelled(true);

        // Mark this dispenser as fired with the current wall-clock time.
        // Cleared when redstone drops to zero or after COOLDOWN_MS has elapsed.
        lastFiredAt.put(key, now);

        // Manually consume one TNT from the dispenser inventory
        consumeOneTNT(dispenserState);

        // Determine the block directly in front of the dispenser
        Block targetBlock = block.getRelative(facing);

        // Spawn TNT exactly at the center of that block with zero velocity.
        // Zero velocity prevents the hitbox from crossing into any adjacent block
        // on the first tick. Water flow or other mechanics will apply forces after.
        int fuseTicks = plugin.getConfig().getInt("cannoning.tnt-fuse-ticks", 80);

        targetBlock.getWorld().spawn(
                targetBlock.getLocation().add(0.5, 0.0, 0.5),
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
