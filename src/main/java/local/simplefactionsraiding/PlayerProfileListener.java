package local.simplefactionsraiding;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerProfileListener implements Listener {

    private enum ProfileType { HUB, FACTIONS, OTHER }

    private static class PlayerState {
        private ItemStack[] contents;
        private ItemStack[] armor;
        private ItemStack offHand;
        private int level;
        private float exp;
        private int totalExp;
        private int food;
        private float saturation;
        private double health;
    }

    private final SimpleFactionsRaidingPlugin plugin;
    private final MultiWorldManager multiWorldManager;
    private final HubCommand hubCommand;

    private final Map<UUID, PlayerState> hubStates = new HashMap<>();
    private final Map<UUID, PlayerState> factionStates = new HashMap<>();

    private final File dataFile;
    private FileConfiguration data;

    public PlayerProfileListener(SimpleFactionsRaidingPlugin plugin,
                                 MultiWorldManager multiWorldManager,
                                 HubCommand hubCommand) {
        this.plugin = plugin;
        this.multiWorldManager = multiWorldManager;
        this.hubCommand = hubCommand;
        this.dataFile = new File(plugin.getDataFolder(), "player_profiles.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> initializeOnJoin(player), 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveCurrentProfile(event.getPlayer());
        multiWorldManager.saveLastLocation(event.getPlayer());
        flushFile();
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        ProfileType from = worldToProfile(event.getFrom().getName());
        ProfileType to = worldToProfile(player.getWorld().getName());

        // Save last location when leaving faction worlds
        if (from == ProfileType.FACTIONS) {
            multiWorldManager.saveLastLocation(player);
        }

        if (from == to) {
            return;
        }

        if (from != ProfileType.OTHER) {
            saveStateToProfile(player, from);
        }

        if (to != ProfileType.OTHER) {
            loadOrInitProfile(player, to);
            afterProfileApplied(player, to);
        }
    }

    public void saveOnlineProfiles() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            saveCurrentProfile(player);
        }
        flushFile();
    }

    private void initializeOnJoin(Player player) {
        ProfileType current = worldToProfile(player.getWorld().getName());
        if (current == ProfileType.OTHER) {
            return;
        }

        Map<UUID, PlayerState> map = mapFor(current);
        if (!map.containsKey(player.getUniqueId())) {
            PlayerState loaded = loadStateFromFile(player.getUniqueId(), current);
            if (loaded != null) {
                map.put(player.getUniqueId(), loaded);
            }
        }

        if (map.containsKey(player.getUniqueId())) {
            applyState(player, map.get(player.getUniqueId()));
        } else {
            saveStateToProfile(player, current);
        }

        afterProfileApplied(player, current);
    }

    private void saveCurrentProfile(Player player) {
        ProfileType current = worldToProfile(player.getWorld().getName());
        if (current == ProfileType.OTHER) {
            return;
        }
        saveStateToProfile(player, current);
    }

    private void saveStateToProfile(Player player, ProfileType profile) {
        PlayerState state = captureState(player);
        mapFor(profile).put(player.getUniqueId(), state);
        saveStateToFile(player.getUniqueId(), profile, state);
    }

    private void loadOrInitProfile(Player player, ProfileType profile) {
        Map<UUID, PlayerState> map = mapFor(profile);
        UUID uuid = player.getUniqueId();

        PlayerState state = map.get(uuid);
        if (state == null) {
            state = loadStateFromFile(uuid, profile);
            if (state != null) {
                map.put(uuid, state);
            }
        }

        if (state == null) {
            state = createEmptyState(player);
            map.put(uuid, state);
            saveStateToFile(uuid, profile, state);
        }

        applyState(player, state);
    }

    private void afterProfileApplied(Player player, ProfileType profile) {
        if (profile == ProfileType.HUB) {
            hubCommand.giveSelectorCompass(player);
        } else if (profile == ProfileType.FACTIONS) {
            stripSelectorCompass(player);
        }
    }

    private void stripSelectorCompass(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;

        for (int i = 0; i < contents.length; i++) {
            if (hubCommand.isSelectorCompass(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }

        ItemStack offHand = inv.getItemInOffHand();
        if (hubCommand.isSelectorCompass(offHand)) {
            inv.setItemInOffHand(null);
            changed = true;
        }

        if (changed) {
            inv.setContents(contents);
        }
    }

    private ProfileType worldToProfile(String worldName) {
        if (worldName == null) {
            return ProfileType.OTHER;
        }

        if (worldName.equalsIgnoreCase(multiWorldManager.getHubWorldName())) {
            return ProfileType.HUB;
        }

        if (worldName.equalsIgnoreCase(multiWorldManager.getFactionWorldName())
                || worldName.equalsIgnoreCase(multiWorldManager.getFactionNetherWorldName())
                || worldName.equalsIgnoreCase(multiWorldManager.getFactionEndWorldName())) {
            return ProfileType.FACTIONS;
        }

        return ProfileType.OTHER;
    }

    private Map<UUID, PlayerState> mapFor(ProfileType profile) {
        return profile == ProfileType.HUB ? hubStates : factionStates;
    }

    private PlayerState captureState(Player player) {
        PlayerState state = new PlayerState();
        PlayerInventory inv = player.getInventory();

        state.contents = cloneItems(inv.getContents());
        state.armor = cloneItems(inv.getArmorContents());
        state.offHand = cloneItem(inv.getItemInOffHand());
        state.level = player.getLevel();
        state.exp = player.getExp();
        state.totalExp = player.getTotalExperience();
        state.food = player.getFoodLevel();
        state.saturation = player.getSaturation();
        state.health = Math.min(player.getHealth(), player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        return state;
    }

    private PlayerState createEmptyState(Player player) {
        PlayerState state = new PlayerState();
        state.contents = new ItemStack[36];
        state.armor = new ItemStack[4];
        state.offHand = null;
        state.level = 0;
        state.exp = 0f;
        state.totalExp = 0;
        state.food = 20;
        state.saturation = 5f;
        state.health = Math.min(20.0, player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        return state;
    }

    private void applyState(Player player, PlayerState state) {
        PlayerInventory inv = player.getInventory();
        inv.setContents(fitArray(state.contents, 36));
        inv.setArmorContents(fitArray(state.armor, 4));
        inv.setItemInOffHand(cloneItem(state.offHand));
        player.setLevel(Math.max(0, state.level));
        player.setExp(Math.max(0f, Math.min(1f, state.exp)));
        player.setTotalExperience(Math.max(0, state.totalExp));
        player.setFoodLevel(Math.max(0, Math.min(20, state.food)));
        player.setSaturation(Math.max(0f, state.saturation));

        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double safeHealth = Math.max(1.0, Math.min(maxHealth, state.health));
        player.setHealth(safeHealth);
    }

    private void saveStateToFile(UUID uuid, ProfileType profile, PlayerState state) {
        String base = uuid.toString() + "." + profile.name().toLowerCase();
        data.set(base + ".contents", state.contents);
        data.set(base + ".armor", state.armor);
        data.set(base + ".offhand", state.offHand);
        data.set(base + ".level", state.level);
        data.set(base + ".exp", state.exp);
        data.set(base + ".totalExp", state.totalExp);
        data.set(base + ".food", state.food);
        data.set(base + ".saturation", state.saturation);
        data.set(base + ".health", state.health);
    }

    @SuppressWarnings("unchecked")
    private PlayerState loadStateFromFile(UUID uuid, ProfileType profile) {
        String base = uuid.toString() + "." + profile.name().toLowerCase();
        if (!data.contains(base)) {
            return null;
        }

        PlayerState state = new PlayerState();
        state.contents = toItemArray((java.util.List<ItemStack>) data.getList(base + ".contents"), 36);
        state.armor = toItemArray((java.util.List<ItemStack>) data.getList(base + ".armor"), 4);
        state.offHand = cloneItem((ItemStack) data.get(base + ".offhand"));
        state.level = data.getInt(base + ".level", 0);
        state.exp = (float) data.getDouble(base + ".exp", 0.0);
        state.totalExp = data.getInt(base + ".totalExp", 0);
        state.food = data.getInt(base + ".food", 20);
        state.saturation = (float) data.getDouble(base + ".saturation", 5.0);
        state.health = data.getDouble(base + ".health", 20.0);
        return state;
    }

    private void flushFile() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save player_profiles.yml: " + e.getMessage());
        }
    }

    private ItemStack[] toItemArray(java.util.List<ItemStack> list, int size) {
        ItemStack[] items = new ItemStack[size];
        if (list == null) {
            return items;
        }
        for (int i = 0; i < size && i < list.size(); i++) {
            items[i] = cloneItem(list.get(i));
        }
        return items;
    }

    private ItemStack[] fitArray(ItemStack[] items, int size) {
        ItemStack[] result = new ItemStack[size];
        if (items == null) {
            return result;
        }
        for (int i = 0; i < size && i < items.length; i++) {
            result[i] = cloneItem(items[i]);
        }
        return result;
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
