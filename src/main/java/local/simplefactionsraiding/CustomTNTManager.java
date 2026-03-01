package local.simplefactionsraiding;

import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/**
 * Manages custom TNT types: Lethal, Gigantic, Lucky
 */
public class CustomTNTManager {

    private final JavaPlugin plugin;
    private final Map<TNTPrimed, CustomTNTType> tntTypes = new WeakHashMap<>();

    public enum CustomTNTType {
        LETHAL(2.0, 20, "§c⚡ LETHAL TNT§r"),         // 2x damage, 20m radius
        GIGANTIC(1.5, 40, "§6✦ GIGANTIC TNT§r"),      // 1.5x damage, 40m radius
        LUCKY(1.0, 30, "§e♻ LUCKY TNT§r");            // 1x damage, randomize nearby ore

        private final double damageMultiplier;
        private final double radius;
        private final String displayName;

        CustomTNTType(double damageMultiplier, double radius, String displayName) {
            this.damageMultiplier = damageMultiplier;
            this.radius = radius;
            this.displayName = displayName;
        }

        public double getDamageMultiplier() {
            return damageMultiplier;
        }

        public double getRadius() {
            return radius;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public CustomTNTManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a custom TNT entity
     */
    public void registerCustomTNT(TNTPrimed tnt, CustomTNTType type) {
        tntTypes.put(tnt, type);
    }

    /**
     * Gets the custom TNT type for an entity
     */
    public CustomTNTType getCustomTNTType(Entity entity) {
        if (entity instanceof TNTPrimed) {
            return tntTypes.getOrDefault((TNTPrimed) entity, null);
        }
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
        return type.getRadius();
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
}
