package local.simplefactionsraiding;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import local.simplefactions.SimpleFactionsPlugin;
import local.simplefactions.FactionManager;

public class SimpleFactionsRaidingPlugin extends JavaPlugin {

    private RaidingManager raidingManager;
    private CustomTNTManager customTNTManager;
    private CoreChunkManager coreChunkManager;
    private CollectionChestManager collectionChestManager;
    private CollectionFilterGUI collectionFilterGUI;
    private CollectionChestListener collectionChestListener;
    private WorldRulesListener worldRulesListener;
    private MultiWorldManager multiWorldManager;
    private HubCommand hubCommand;
    private PlayerProfileListener playerProfileListener;
    private AutoRestartManager autoRestartManager;

    @Override
    public void onEnable() {
        getLogger().info("=== SimpleFactionsRaiding Plugin Enabled ===");
        saveDefaultConfig();

        this.multiWorldManager = new MultiWorldManager(this);
        this.multiWorldManager.ensureWorlds();

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
        this.collectionChestManager = new CollectionChestManager(this);
        this.collectionFilterGUI = new CollectionFilterGUI(collectionChestManager, this);
        this.collectionChestListener = new CollectionChestListener(collectionChestManager, collectionFilterGUI);
        this.worldRulesListener = new WorldRulesListener(this, multiWorldManager);
        this.hubCommand = new HubCommand(this, multiWorldManager);
        this.playerProfileListener = new PlayerProfileListener(this, multiWorldManager, hubCommand);
        this.autoRestartManager = new AutoRestartManager(this, multiWorldManager);

        // Register listeners
        getServer().getPluginManager().registerEvents(new RaidListener(this, raidingManager, customTNTManager, coreChunkManager), this);
        getServer().getPluginManager().registerEvents(new CustomExplosiveListener(this, customTNTManager), this);
        getServer().getPluginManager().registerEvents(new DispenserTNTListener(this), this);
        getServer().getPluginManager().registerEvents(new TNTPhysicsListener(this), this);
        getServer().getPluginManager().registerEvents(new FactionTntCommandListener(this, factionManager), this);
        getServer().getPluginManager().registerEvents(new CannonConsistencyListener(this), this);
        getServer().getPluginManager().registerEvents(collectionChestListener, this);
        getServer().getPluginManager().registerEvents(worldRulesListener, this);
        getServer().getPluginManager().registerEvents(new HubSelectorListener(multiWorldManager, hubCommand), this);
        getServer().getPluginManager().registerEvents(playerProfileListener, this);

        getLogger().info("/f tnt commands handled by FactionTntCommandListener.");

        // Register commands
        getCommand("raid").setExecutor(new RaidCommand(this, raidingManager, coreChunkManager));
        getCommand("collectionfilter").setExecutor(new CollectionChestCommand(collectionChestManager, collectionFilterGUI));
        getCommand("createcollectionchest").setExecutor(new CollectionChestCommand(collectionChestManager, collectionFilterGUI));
        getCommand("customtnt").setExecutor(new CustomExplosiveCommand(customTNTManager, CustomExplosiveCommand.CommandKind.TNT));
        getCommand("customcreeper").setExecutor(new CustomExplosiveCommand(customTNTManager, CustomExplosiveCommand.CommandKind.CREEPER));
        WildernessCommand wildernessCommand = new WildernessCommand(this);
        getCommand("wild").setExecutor(wildernessCommand);
        getCommand("widlerness").setExecutor(wildernessCommand);
        getCommand("hub").setExecutor(hubCommand);
        getCommand("server").setExecutor(new ServerCommand(multiWorldManager));
        getCommand("worldreset").setExecutor(new WorldResetCommand(this, multiWorldManager));
        RestartAdminCommand restartAdminCommand = new RestartAdminCommand(autoRestartManager);
        getCommand("restartnow").setExecutor(restartAdminCommand);
        getCommand("forcerestart").setExecutor(restartAdminCommand);

        // /pastecannon — WorldEdit schematic paste for cannon testing
        if (getServer().getPluginManager().getPlugin("WorldEdit") != null) {
            PasteCannonCommand pasteCmd = new PasteCannonCommand(this);
            getCommand("pastecannon").setExecutor(pasteCmd);
            getCommand("pastecannon").setTabCompleter(pasteCmd);
            getLogger().info("WorldEdit found — /pastecannon enabled.");
        } else {
            getLogger().warning("WorldEdit not found — /pastecannon will not be available.");
        }

        getLogger().info("Raiding managers initialized successfully");
        worldRulesListener.applyWorldRulesNow();
        getLogger().info("Applied wilderness world rules (border + bedrock layer).");
        autoRestartManager.start();
    }

    @Override
    public void onDisable() {
        if (autoRestartManager != null) {
            autoRestartManager.stop();
        }
        if (playerProfileListener != null) {
            playerProfileListener.saveOnlineProfiles();
        }
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

    public CollectionChestManager getCollectionChestManager() {
        return collectionChestManager;
    }

    public CollectionFilterGUI getCollectionFilterGUI() {
        return collectionFilterGUI;
    }

}
