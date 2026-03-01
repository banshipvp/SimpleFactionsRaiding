package local.simplefactionsraiding;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import local.simplefactions.SimpleFactionsPlugin;
import local.simplefactions.FactionManager;

public class SimpleFactionsRaidingPlugin extends JavaPlugin {

    private RaidingManager raidingManager;
    private CustomTNTManager customTNTManager;
    private CoreChunkManager coreChunkManager;

    @Override
    public void onEnable() {
        getLogger().info("=== SimpleFactionsRaiding Plugin Enabled ===");
        saveDefaultConfig();

        // Check if SimpleFactions is loaded
        if (Bukkit.getPluginManager().getPlugin("SimpleFactions") == null) {
            getLogger().severe("SimpleFactions not found! Disabling SimpleFactionsRaiding.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        SimpleFactionsPlugin simpleFactionsPlugin = (SimpleFactionsPlugin) Bukkit.getPluginManager().getPlugin("SimpleFactions");
        FactionManager factionManager = simpleFactionsPlugin.getFactionManager();

        // Initialize managers
        this.raidingManager = new RaidingManager(this);
        this.customTNTManager = new CustomTNTManager(this);
        this.coreChunkManager = new CoreChunkManager(this, factionManager);

        // Register listeners
        getServer().getPluginManager().registerEvents(new RaidListener(this, raidingManager, customTNTManager, coreChunkManager), this);

        getLogger().info("/f tnt commands are delegated to SimpleFactions native handler.");

        // Register commands
        getCommand("raid").setExecutor(new RaidCommand(this, raidingManager, coreChunkManager));

        getLogger().info("Raiding managers initialized successfully");
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleFactionsRaiding Plugin Disabled");
    }

    public RaidingManager getRaidingManager() {
        return raidingManager;
    }

    public CustomTNTManager getCustomTNTManager() {
        return customTNTManager;
    }

    public CoreChunkManager getCoreChunkManager() {
        return coreChunkManager;
    }

}
