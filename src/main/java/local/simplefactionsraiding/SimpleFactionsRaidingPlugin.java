package local.simplefactionsraiding;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import local.simplefactions.SimpleFactionsPlugin;
import local.simplefactions.FactionManager;
import local.simplefactions.HubQueueManager;

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
    private ServerStatusManager serverStatusManager;
    private SimpleFactionsPlugin simpleFactionsPlugin;

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

        this.simpleFactionsPlugin = (SimpleFactionsPlugin) Bukkit.getPluginManager().getPlugin("SimpleFactions");
        FactionManager factionManager = simpleFactionsPlugin.getFactionManager();

        HubQueueManager queueManager = simpleFactionsPlugin.getHubQueueManager();
        if (queueManager != null) {
            queueManager.setTransferHandler(player -> multiWorldManager.teleportToFactionServer(player));
            getLogger().info("Configured SimpleFactions queue to local faction-world teleport mode.");
        }

        // Initialize managers
        this.raidingManager = new RaidingManager(this);
        this.customTNTManager = new CustomTNTManager(this);
        this.coreChunkManager = new CoreChunkManager(this, factionManager);
        this.collectionChestManager = new CollectionChestManager(this);
        this.collectionChestManager.loadData(getDataFolder());
        this.collectionFilterGUI = new CollectionFilterGUI(collectionChestManager, this);
        this.collectionChestListener = new CollectionChestListener(collectionChestManager, collectionFilterGUI);
        this.worldRulesListener = new WorldRulesListener(this, multiWorldManager);
        this.hubCommand = new HubCommand(this, multiWorldManager);
        this.playerProfileListener = new PlayerProfileListener(this, multiWorldManager, hubCommand);
        this.autoRestartManager = new AutoRestartManager(this, multiWorldManager);
        this.serverStatusManager = new ServerStatusManager(this);
        this.autoRestartManager.setServerStatusManager(serverStatusManager);

        // Register listeners
        getServer().getPluginManager().registerEvents(new RaidListener(this, raidingManager, customTNTManager, coreChunkManager), this);
        getServer().getPluginManager().registerEvents(new CustomExplosiveListener(this, customTNTManager), this);
        getServer().getPluginManager().registerEvents(new DispenserTNTListener(this), this);
        TNTPhysicsListener tntPhysics = new TNTPhysicsListener(this);
        getServer().getPluginManager().registerEvents(tntPhysics, this);
        getServer().getPluginManager().registerEvents(new FactionTntCommandListener(this, factionManager), this);
        getServer().getPluginManager().registerEvents(new CannonConsistencyListener(this), this);
        getServer().getPluginManager().registerEvents(collectionChestListener, this);
        getServer().getPluginManager().registerEvents(worldRulesListener, this);
        getServer().getPluginManager().registerEvents(new HubSelectorListener(multiWorldManager, hubCommand, simpleFactionsPlugin, serverStatusManager), this);
        getServer().getPluginManager().registerEvents(playerProfileListener, this);
        getServer().getPluginManager().registerEvents(serverStatusManager, this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this, multiWorldManager), this);

        getLogger().info("/f tnt commands handled by FactionTntCommandListener.");

        // Register commands
        getCommand("raid").setExecutor(new RaidCommand(this, raidingManager, coreChunkManager));
        SimpleFactionsRaidingTabCompleter tabCompleter = new SimpleFactionsRaidingTabCompleter(coreChunkManager);
        getCommand("raid").setTabCompleter(tabCompleter);
        getCommand("collectionfilter").setExecutor(new CollectionChestCommand(collectionChestManager, collectionFilterGUI));
        getCommand("createcollectionchest").setExecutor(new CollectionChestCommand(collectionChestManager, collectionFilterGUI));
        getCommand("createcollectionchest").setTabCompleter(tabCompleter);
        getCommand("customtnt").setExecutor(new CustomExplosiveCommand(customTNTManager, CustomExplosiveCommand.CommandKind.TNT));
        getCommand("customtnt").setTabCompleter(tabCompleter);
        getCommand("customcreeper").setExecutor(new CustomExplosiveCommand(customTNTManager, CustomExplosiveCommand.CommandKind.CREEPER));
        getCommand("customcreeper").setTabCompleter(tabCompleter);
        WildernessCommand wildernessCommand = new WildernessCommand(this);
        getCommand("wild").setExecutor(wildernessCommand);
        getCommand("widlerness").setExecutor(wildernessCommand);
        getCommand("hub").setExecutor(hubCommand);
        getCommand("server").setExecutor(new ServerCommand(multiWorldManager, simpleFactionsPlugin));
        getCommand("server").setTabCompleter(tabCompleter);
        getCommand("spawn").setExecutor(new SpawnCommand(this, multiWorldManager));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("worldreset").setExecutor(new WorldResetCommand(this, multiWorldManager));
        getCommand("worldreset").setTabCompleter(tabCompleter);
        RestartAdminCommand restartAdminCommand = new RestartAdminCommand(autoRestartManager);
        getCommand("restartnow").setExecutor(restartAdminCommand);
        getCommand("forcerestart").setExecutor(restartAdminCommand);
        getCommand("rebootforce").setExecutor(restartAdminCommand);
        getCommand("reboot").setExecutor(new RebootCommand(autoRestartManager));
        getCommand("staff").setExecutor(new StaffOnlineCommand());
        ServerOpenCloseCommand serverOpenCloseCommand = new ServerOpenCloseCommand(serverStatusManager);
        getCommand("serveropen").setExecutor(serverOpenCloseCommand);
        getCommand("serverclose").setExecutor(serverOpenCloseCommand);

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

        // Schedule auto-converter task (TNT→Gunpowder, Ingot→Block) every 5 seconds
        final FactionManager fmRef = factionManager;
        Bukkit.getScheduler().runTaskTimer(this, new ChestAutoConverterTask(collectionChestManager, fmRef), 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (collectionChestManager != null) {
            collectionChestManager.saveData(getDataFolder());
        }
        if (simpleFactionsPlugin != null && simpleFactionsPlugin.getHubQueueManager() != null) {
            simpleFactionsPlugin.getHubQueueManager().setTransferHandler(null);
        }
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

    public ServerStatusManager getServerStatusManager() {
        return serverStatusManager;
    }

}
