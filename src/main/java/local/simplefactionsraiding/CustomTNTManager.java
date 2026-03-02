package local.simplefactionsraiding;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/**
 * Manages custom TNT types: Lethal, Gigantic, Lucky
 */
public class CustomTNTManager {

    private final JavaPlugin plugin;
    private final Map<TNTPrimed, CustomTNTType> tntTypes = new WeakHashMap<>();
    private final Map<Creeper, CustomCreeperType> creeperTypes = new WeakHashMap<>();
    private final NamespacedKey tntTypeKey;
    private final NamespacedKey creeperTypeKey;
    private final NamespacedKey itemTypeKey;

    public enum CustomTNTType {
        LETHAL(2.0, 1.5, 0.50, 2.0, "§c⚡ LETHAL TNT§r"),
        GIGANTIC(3.0, 2.6, 0.50, 10.0, "§6✦ GIGANTIC TNT§r"),
        LUCKY(1.0, 1.0, 0.95, 1.0, "§e♻ LUCKY TNT§r");

        private final double damageMultiplier;
        private final double radiusMultiplier;
        private final double spawnerDropChance;
        private final double raidPointMultiplier;
        private final String displayName;

        CustomTNTType(double damageMultiplier, double radiusMultiplier, double spawnerDropChance,
                      double raidPointMultiplier, String displayName) {
            this.damageMultiplier = damageMultiplier;
            this.radiusMultiplier = radiusMultiplier;
            this.spawnerDropChance = spawnerDropChance;
            this.raidPointMultiplier = raidPointMultiplier;
            this.displayName = displayName;
        }

        public double getDamageMultiplier() {
            return damageMultiplier;
        }

        public double getRadiusMultiplier() {
            return radiusMultiplier;
        }

        public double getSpawnerDropChance() {
            return spawnerDropChance;
        }

        public double getRaidPointMultiplier() {
            return raidPointMultiplier;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum CustomCreeperType {
        LETHAL(2.0, 1.5, 0.50, "§c⚡ LETHAL CREEPER EGG§r"),
        GIGANTIC(3.0, 2.6, 0.50, "§6✦ GIGANTIC CREEPER EGG§r"),
        LUCKY(1.0, 1.0, 0.95, "§e♻ LUCKY CREEPER EGG§r");

        private final double damageMultiplier;
        private final double radiusMultiplier;
        private final double spawnerDropChance;
        private final String displayName;

        CustomCreeperType(double damageMultiplier, double radiusMultiplier, double spawnerDropChance, String displayName) {
            this.damageMultiplier = damageMultiplier;
            this.radiusMultiplier = radiusMultiplier;
            this.spawnerDropChance = spawnerDropChance;
            this.displayName = displayName;
        }

        public double getDamageMultiplier() {
            return damageMultiplier;
        }

        public double getRadiusMultiplier() {
            return radiusMultiplier;
        }

        public double getSpawnerDropChance() {
            return spawnerDropChance;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public CustomTNTManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.tntTypeKey = new NamespacedKey(plugin, "custom_tnt_type");
        this.creeperTypeKey = new NamespacedKey(plugin, "custom_creeper_type");
        this.itemTypeKey = new NamespacedKey(plugin, "custom_explosive_type");
    }

    /**
     * Registers a custom TNT entity
     */
    public void registerCustomTNT(TNTPrimed tnt, CustomTNTType type) {
        tntTypes.put(tnt, type);
        PersistentDataContainer pdc = tnt.getPersistentDataContainer();
        pdc.set(tntTypeKey, PersistentDataType.STRING, type.name());
        tnt.setCustomName(type.getDisplayName());
        tnt.setCustomNameVisible(false);
    }

    /**
     * Gets the custom TNT type for an entity
     */
    public CustomTNTType getCustomTNTType(Entity entity) {
        if (entity instanceof TNTPrimed tnt) {
            CustomTNTType memoryType = tntTypes.get(tnt);
            if (memoryType != null) {
                return memoryType;
            }

            String stored = tnt.getPersistentDataContainer().get(tntTypeKey, PersistentDataType.STRING);
            if (stored == null) {
                return null;
            }

            try {
                CustomTNTType parsed = CustomTNTType.valueOf(stored);
                tntTypes.put(tnt, parsed);
                return parsed;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public void registerCustomCreeper(Creeper creeper, CustomCreeperType type) {
        creeperTypes.put(creeper, type);
        PersistentDataContainer pdc = creeper.getPersistentDataContainer();
        pdc.set(creeperTypeKey, PersistentDataType.STRING, type.name());
        creeper.setCustomName(type.getDisplayName());
        creeper.setCustomNameVisible(false);
    }

    public CustomCreeperType getCustomCreeperType(Entity entity) {
        if (entity instanceof Creeper creeper) {
            CustomCreeperType memoryType = creeperTypes.get(creeper);
            if (memoryType != null) {
                return memoryType;
            }

            String stored = creeper.getPersistentDataContainer().get(creeperTypeKey, PersistentDataType.STRING);
            if (stored == null) {
                return null;
            }

            try {
                CustomCreeperType parsed = CustomCreeperType.valueOf(stored);
                creeperTypes.put(creeper, parsed);
                return parsed;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public ItemStack createCustomTNTItem(CustomTNTType type, int amount) {
        ItemStack item = new ItemStack(Material.TNT, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(type.getDisplayName());
            meta.setLore(List.of(
                    "§7Custom TNT variant",
                    "§7Damage: §f" + formatMultiplier(type.getDamageMultiplier()) + "x",
                    "§7Radius: §f" + formatMultiplier(type.getRadiusMultiplier()) + "x",
                    type == CustomTNTType.LUCKY ? "§7Spawner Drop: §a95%" : "§7Spawner Drop: §e50%"
            ));
            meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, "TNT:" + type.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createCustomCreeperEggItem(CustomCreeperType type, int amount) {
        ItemStack item = new ItemStack(Material.CREEPER_SPAWN_EGG, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(type.getDisplayName());
            meta.setLore(List.of(
                    "§7Spawn a custom creeper variant",
                    "§7Damage: §f" + formatMultiplier(type.getDamageMultiplier()) + "x",
                    "§7Radius: §f" + formatMultiplier(type.getRadiusMultiplier()) + "x",
                    type == CustomCreeperType.LUCKY ? "§7Spawner Drop: §a95%" : "§7Spawner Drop: §e50%"
            ));
            meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, "CREEPER:" + type.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    public CustomTNTType identifyTNTItem(ItemStack item) {
        if (item == null || item.getType() != Material.TNT || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
        if (stored != null && stored.startsWith("TNT:")) {
            return parseTntType(stored.substring(4));
        }

        String plainName = strip(meta.getDisplayName());
        if (plainName.contains("LETHAL TNT")) return CustomTNTType.LETHAL;
        if (plainName.contains("GIGANTIC TNT")) return CustomTNTType.GIGANTIC;
        if (plainName.contains("LUCKY TNT")) return CustomTNTType.LUCKY;
        return null;
    }

    public CustomCreeperType identifyCreeperEggItem(ItemStack item) {
        if (item == null || item.getType() != Material.CREEPER_SPAWN_EGG || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
        if (stored != null && stored.startsWith("CREEPER:")) {
            return parseCreeperType(stored.substring(8));
        }

        String plainName = strip(meta.getDisplayName());
        if (plainName.contains("LETHAL CREEPER")) return CustomCreeperType.LETHAL;
        if (plainName.contains("GIGANTIC CREEPER")) return CustomCreeperType.GIGANTIC;
        if (plainName.contains("LUCKY CREEPER")) return CustomCreeperType.LUCKY;
        return null;
    }

    /**
     * Checks if TNT is a custom variant
     */
    public boolean isCustomTNT(Entity entity) {
        return getCustomTNTType(entity) != null;
    }

    /**
     * Gets explosion radius for custom TNT type
     */
    public double getExplosionRadius(CustomTNTType type) {
        return 4.0 * type.getRadiusMultiplier();
    }

    /**
     * Gets damage multiplier for custom TNT type
     */
    public double getDamageMultiplier(CustomTNTType type) {
        return type.getDamageMultiplier();
    }

    /**
     * Gets display name for custom TNT type
     */
    public String getDisplayName(CustomTNTType type) {
        return type.getDisplayName();
    }

    public double getRaidPointMultiplier(CustomTNTType type) {
        return type.getRaidPointMultiplier();
    }

    public double getSpawnerDropChance(CustomTNTType type) {
        return type.getSpawnerDropChance();
    }

    public double getExplosionRadius(CustomCreeperType type) {
        return 3.0 * type.getRadiusMultiplier();
    }

    public double getDamageMultiplier(CustomCreeperType type) {
        return type.getDamageMultiplier();
    }

    public double getSpawnerDropChance(CustomCreeperType type) {
        return type.getSpawnerDropChance();
    }

    private CustomTNTType parseTntType(String value) {
        try {
            return CustomTNTType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private CustomCreeperType parseCreeperType(String value) {
        try {
            return CustomCreeperType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String strip(String input) {
        if (input == null) return "";
        return input.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toUpperCase(Locale.ROOT);
    }

    private String formatMultiplier(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }
}
