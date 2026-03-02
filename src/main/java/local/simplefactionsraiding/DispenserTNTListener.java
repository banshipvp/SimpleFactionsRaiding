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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Fixes TNT dispenser physics so that TNT entities spawn exactly centered
 * inside the block they land in, with zero initial velocity. This prevents
 * the vanilla off-center spawn position from causing the TNT hitbox to
 * clip into adjacent blocks (a common cannoning bug).
 */
public class DispenserTNTListener implements Listener {

    private final JavaPlugin plugin;
    private final boolean enabled;

    public DispenserTNTListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("cannoning.fix-dispenser-tnt-physics", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseTNT(BlockDispenseEvent event) {
        if (!enabled) return;
        if (event.getItem().getType() != Material.TNT) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Dispenser)) return;

        Dispenser dispenserState = (Dispenser) block.getState();
        BlockFace facing = getFacing(block);
        if (facing == null) return;

        // Cancel the vanilla dispense so we control the spawn
        event.setCancelled(true);

        // Manually consume one TNT from the dispenser inventory
        consumeOneTNT(dispenserState);

        // Determine the block directly in front of the dispenser
        Block targetBlock = block.getRelative(facing);

        // Spawn TNT exactly at the center of that block with zero velocity.
        // Zero velocity prevents the hitbox from crossing into any adjacent block
        // on the first tick. Water flow or other mechanics will apply forces after.
        int fuseTicks = plugin.getConfig().getInt("cannoning.tnt-fuse-ticks", 80);

        targetBlock.getWorld().spawn(
                targetBlock.getLocation().add(0.5, 0.5, 0.5),
                TNTPrimed.class,
                tnt -> {
                    tnt.setVelocity(new Vector(0, 0, 0));
                    tnt.setFuseTicks(fuseTicks);
                }
        );
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
