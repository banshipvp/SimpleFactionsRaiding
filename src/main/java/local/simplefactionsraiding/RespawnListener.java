package local.simplefactionsraiding;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Forces players to respawn at the configured custom spawn for whatever world
 * they died in (stored under custom-spawns.<worldName> by /setspawn).
 *
 * Priority HIGH so we run after most plugins but before HIGHEST/MONITOR ones.
 */
public class RespawnListener implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;

    public RespawnListener(SimpleFactionsRaidingPlugin plugin, MultiWorldManager multiWorldManager) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World deathWorld = player.getWorld();
        String worldName = deathWorld.getName();

        Location spawnLoc = getConfiguredSpawn(worldName, deathWorld);

        // If the player died in a non-overworld faction world (nether/end),
        // respawn them at the overworld faction spawn, not in the nether/end.
        if (worldName.equalsIgnoreCase(multiWorldManager.getFactionNetherWorldName())
                || worldName.equalsIgnoreCase(multiWorldManager.getFactionEndWorldName())) {
            String overworld = multiWorldManager.getFactionWorldName();
            World factionWorld = multiWorldManager.getFactionWorld();
            spawnLoc = getConfiguredSpawn(overworld, factionWorld);
        }

        if (spawnLoc != null) {
            event.setRespawnLocation(spawnLoc);
        }
    }

    /**
     * Returns the custom spawn for {@code worldName} stored by /setspawn, or falls
     * back to {@code world.getSpawnLocation()} if no custom spawn is configured.
     * Returns null only if the world itself is null.
     */
    private Location getConfiguredSpawn(String worldName, World world) {
        if (world == null) return null;

        String path = "custom-spawns." + worldName;
        if (plugin.getConfig().contains(path)) {
            double x     = plugin.getConfig().getDouble(path + ".x");
            double y     = plugin.getConfig().getDouble(path + ".y");
            double z     = plugin.getConfig().getDouble(path + ".z");
            float  yaw   = (float) plugin.getConfig().getDouble(path + ".yaw");
            float  pitch = (float) plugin.getConfig().getDouble(path + ".pitch");
            return new Location(world, x, y, z, yaw, pitch);
        }

        // Fallback: Bukkit world spawn (what /setworldspawn sets)
        return world.getSpawnLocation().clone().add(0.5, 0.1, 0.5);
    }
}
