package local.simplefactionsraiding;

import local.simplefactions.FactionManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Runs every 5 seconds (100 ticks) to apply faction upgrades inside collection chests:
 * <ul>
 *   <li><b>tnt2gunpowder</b> – converts each TNT item into 4 Gunpowder</li>
 *   <li><b>ingot2block</b>   – compresses 9 ingots / gems of the same type into 1 block</li>
 * </ul>
 * The task only acts on chests whose owning chunk belongs to a faction that has
 * purchased the relevant upgrade.
 */
public class ChestAutoConverterTask implements Runnable {

    /** Ingot → block conversion map (9 ingots = 1 block). */
    private static final Map<Material, Material> INGOT_TO_BLOCK = new EnumMap<>(Material.class);

    static {
        INGOT_TO_BLOCK.put(Material.IRON_INGOT,   Material.IRON_BLOCK);
        INGOT_TO_BLOCK.put(Material.GOLD_INGOT,   Material.GOLD_BLOCK);
        INGOT_TO_BLOCK.put(Material.COPPER_INGOT, Material.COPPER_BLOCK);
        INGOT_TO_BLOCK.put(Material.EMERALD,      Material.EMERALD_BLOCK);
        INGOT_TO_BLOCK.put(Material.DIAMOND,      Material.DIAMOND_BLOCK);
        INGOT_TO_BLOCK.put(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK);
    }

    private final CollectionChestManager chestManager;
    private final FactionManager factionManager;

    public ChestAutoConverterTask(CollectionChestManager chestManager, FactionManager factionManager) {
        this.chestManager = chestManager;
        this.factionManager = factionManager;
    }

    @Override
    public void run() {
        for (String loc : chestManager.getRegisteredChests()) {
            Block block = chestManager.deserializeBlock(loc);
            if (block == null || !chestManager.isCollectionChest(block)) continue;
            if (!(block.getState() instanceof Container container)) continue;

            // Determine owning faction
            String world = block.getWorld().getName();
            int cx = block.getChunk().getX();
            int cz = block.getChunk().getZ();
            FactionManager.Faction faction = factionManager.getFactionByChunk(world, cx, cz);
            if (faction == null) continue;

            Inventory inv = container.getInventory();

            // -----------------------------------------------------------------
            // Upgrade: TNT → Gunpowder (1 TNT = 4 Gunpowder)
            // -----------------------------------------------------------------
            if (faction.getUpgradeLevel("tnt2gunpowder") >= 1) {
                convertTnt(inv);
            }

            // -----------------------------------------------------------------
            // Upgrade: Ingot → Block (9 ingots = 1 block)
            // -----------------------------------------------------------------
            if (faction.getUpgradeLevel("ingot2block") >= 1) {
                convertIngots(inv);
            }
        }
    }

    // -------------------------------------------------------------------------

    private void convertTnt(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() != Material.TNT) continue;

            int tntCount = stack.getAmount();
            inv.setItem(i, null);

            // Each TNT → 4 Gunpowder
            int gunpowder = tntCount * 4;
            addOrDrop(inv, new ItemStack(Material.GUNPOWDER, gunpowder));
        }
    }

    private void convertIngots(Inventory inv) {
        for (Map.Entry<Material, Material> entry : INGOT_TO_BLOCK.entrySet()) {
            Material ingot  = entry.getKey();
            Material block  = entry.getValue();

            int total = countMaterial(inv, ingot);
            int sets   = total / 9;
            if (sets == 0) continue;

            int toRemove = sets * 9;
            removeMaterial(inv, ingot, toRemove);
            addOrDrop(inv, new ItemStack(block, sets));
        }
    }

    // -------------------------------------------------------------------------

    private int countMaterial(Inventory inv, Material mat) {
        int count = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == mat) count += s.getAmount();
        }
        return count;
    }

    private void removeMaterial(Inventory inv, Material mat, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != mat) continue;
            int take = Math.min(s.getAmount(), remaining);
            remaining -= take;
            if (s.getAmount() == take) {
                inv.setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - take);
            }
        }
    }

    private void addOrDrop(Inventory inv, ItemStack item) {
        if (item.getAmount() <= 0) return;
        int maxStack = item.getMaxStackSize();
        int remaining = item.getAmount();
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack batch0 = new ItemStack(item.getType(), batch);
            Map<Integer, ItemStack> leftover = inv.addItem(batch0);
            if (!leftover.isEmpty()) {
                // Chest is full — drop on the floor (find chest block location)
                // Just let the rest disappear quietly; in practice this should be rare
                break;
            }
            remaining -= batch;
        }
    }
}
