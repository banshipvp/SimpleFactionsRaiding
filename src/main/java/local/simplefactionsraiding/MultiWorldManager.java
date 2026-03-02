package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

public class MultiWorldManager {

    private final SimpleFactionsRaidingPlugin plugin;

    public MultiWorldManager(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureWorlds() {
        createNormalWorld(getHubWorldName());
        createNormalWorld(getFactionWorldName());
        createWorld(getFactionNetherWorldName(), World.Environment.NETHER);
        createWorld(getFactionEndWorldName(), World.Environment.THE_END);
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
        Location spawn = faction.getSpawnLocation().clone().add(0.5, 0.1, 0.5);
        return player.teleport(spawn);
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
