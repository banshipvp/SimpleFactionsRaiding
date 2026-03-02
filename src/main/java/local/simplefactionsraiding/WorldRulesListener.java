package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class WorldRulesListener implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;

    public WorldRulesListener(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
    }

    public void applyWorldRulesNow() {
        int borderSize = Math.max(100, plugin.getConfig().getInt("wilderness.world-border-size", 5000));
        double centerX = plugin.getConfig().getDouble("wilderness.world-border-center-x", 0.0);
        double centerZ = plugin.getConfig().getDouble("wilderness.world-border-center-z", 0.0);

        World world = multiWorldManager.getFactionWorld();
        if (world != null) {

            WorldBorder border = world.getWorldBorder();
            border.setCenter(centerX, centerZ);
            border.setSize(borderSize);

            // Apply bedrock-at-y0 to already loaded chunks on startup
            world.getLoadedChunks();
            for (var chunk : world.getLoadedChunks()) {
                enforceBedrockLayer(chunk.getWorld(), chunk.getX(), chunk.getZ());
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World factionWorld = multiWorldManager.getFactionWorld();
        if (factionWorld == null || !event.getWorld().getName().equalsIgnoreCase(factionWorld.getName())) {
            return;
        }
        enforceBedrockLayer(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private void enforceBedrockLayer(World world, int chunkX, int chunkZ) {
        int minHeight = world.getMinHeight();
        // keep exactly requested behavior: bedrock at y=0 in normal world
        int y = 0;
        if (y < minHeight) {
            return;
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Block block = world.getBlockAt(baseX + dx, y, baseZ + dz);
                if (block.getType() != Material.BEDROCK) {
                    block.setType(Material.BEDROCK, false);
                }
            }
        }
    }
}
