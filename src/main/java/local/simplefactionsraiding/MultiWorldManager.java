package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class MultiWorldManager {

    private final SimpleFactionsRaidingPlugin plugin;
    private final File locationsFile;
    private FileConfiguration locationsData;

    public MultiWorldManager(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
        this.locationsFile = new File(plugin.getDataFolder(), "last_locations.yml");
        loadLocationsData();
    }

    public void ensureWorlds() {
        createNormalWorld(getHubWorldName());
        createNormalWorld(getFactionWorldName());
        createWorld(getFactionNetherWorldName(), World.Environment.NETHER);
        createWorld(getFactionEndWorldName(), World.Environment.THE_END);
    }

    private void loadLocationsData() {
        if (!locationsFile.exists()) {
            try {
                locationsFile.getParentFile().mkdirs();
                locationsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create last_locations.yml: " + e.getMessage());
            }
        }
        locationsData = YamlConfiguration.loadConfiguration(locationsFile);
    }

    private void saveLocationsData() {
        try {
            locationsData.save(locationsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save last_locations.yml: " + e.getMessage());
        }
    }

    public String getHubWorldName() {
        return plugin.getConfig().getString("worlds.hub", "hub");
    }

    public String getFactionWorldName() {
        return plugin.getConfig().getString("worlds.faction-spawn", "faction_spawn");
    }

    public String getFactionNetherWorldName() {
        return plugin.getConfig().getString("worlds.faction-nether", "faction_nether");
    }

    public String getFactionEndWorldName() {
        return plugin.getConfig().getString("worlds.faction-end", "faction_end");
    }

    public World getHubWorld() {
        return Bukkit.getWorld(getHubWorldName());
    }

    public World getFactionWorld() {
        return Bukkit.getWorld(getFactionWorldName());
    }

    public World getFactionNetherWorld() {
        return Bukkit.getWorld(getFactionNetherWorldName());
    }

    public World getFactionEndWorld() {
        return Bukkit.getWorld(getFactionEndWorldName());
    }

    public boolean teleportToHub(org.bukkit.entity.Player player) {
        World hub = getHubWorld();
        if (hub == null) {
            return false;
        }
        Location spawn = hub.getSpawnLocation().clone().add(0.5, 0.1, 0.5);
        return player.teleport(spawn);
    }

    public boolean teleportToFactionServer(org.bukkit.entity.Player player) {
        World faction = getFactionWorld();
        if (faction == null) {
            return false;
        }
        
        // Try to load last location from player data
        Location lastLocation = loadLastLocation(player);
        plugin.getLogger().info("Attempting to teleport " + player.getName() + " to faction server");
        plugin.getLogger().info("Last location: " + (lastLocation != null ? lastLocation.toString() : "null"));
        
        if (lastLocation != null && lastLocation.getWorld() != null 
                && (lastLocation.getWorld().equals(faction) 
                    || lastLocation.getWorld().equals(getFactionNetherWorld())
                    || lastLocation.getWorld().equals(getFactionEndWorld()))) {
            plugin.getLogger().info("Teleporting to last location: " + lastLocation);
            return player.teleport(lastLocation);
        }
        
        // Fallback to spawn if no last location
        plugin.getLogger().info("No valid last location, teleporting to spawn");
        Location spawn = faction.getSpawnLocation().clone().add(0.5, 0.1, 0.5);
        return player.teleport(spawn);
    }

    public void saveLastLocation(org.bukkit.entity.Player player) {
        if (player.getWorld().equals(getFactionWorld()) 
                || player.getWorld().equals(getFactionNetherWorld())
                || player.getWorld().equals(getFactionEndWorld())) {
            String path = player.getUniqueId().toString();
            plugin.getLogger().info("Saving last location for " + player.getName() + " at " + player.getLocation());
            locationsData.set(path + ".world", player.getWorld().getName());
            locationsData.set(path + ".x", player.getLocation().getX());
            locationsData.set(path + ".y", player.getLocation().getY());
            locationsData.set(path + ".z", player.getLocation().getZ());
            locationsData.set(path + ".yaw", player.getLocation().getYaw());
            locationsData.set(path + ".pitch", player.getLocation().getPitch());
            saveLocationsData();
        } else {
            plugin.getLogger().info("Not saving location for " + player.getName() + " - not in faction world (current: " + player.getWorld().getName() + ")");
        }
    }

    private Location loadLastLocation(org.bukkit.entity.Player player) {
        String path = player.getUniqueId().toString();
        plugin.getLogger().info("Loading last location for " + player.getName() + ", path exists: " + locationsData.contains(path));
        
        if (!locationsData.contains(path)) {
            return null;
        }
        
        String worldName = locationsData.getString(path + ".world");
        plugin.getLogger().info("Stored world name: " + worldName);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return null;
        }
        
        double x = locationsData.getDouble(path + ".x");
        double y = locationsData.getDouble(path + ".y");
        double z = locationsData.getDouble(path + ".z");
        float yaw = (float) locationsData.getDouble(path + ".yaw");
        float pitch = (float) locationsData.getDouble(path + ".pitch");
        
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void createNormalWorld(String name) {
        createWorld(name, World.Environment.NORMAL);
    }

    private void createWorld(String name, World.Environment environment) {
        if (Bukkit.getWorld(name) != null) {
            return;
        }

        WorldCreator creator = new WorldCreator(name)
                .environment(environment)
                .type(WorldType.NORMAL)
                .generateStructures(true);
        creator.createWorld();
    }
}
