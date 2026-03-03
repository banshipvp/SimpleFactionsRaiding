package local.simplefactionsraiding;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class CustomExplosiveListener implements Listener {

    private static final String BLOCK_META_KEY = "sfr_custom_tnt_type";
    private static final long PRIME_MATCH_TTL_MS = 1500L;
    private static final double LETHAL_PROJECTION_RADIUS = 8.0;
    private static final double LETHAL_PROJECTION_MULTIPLIER = 1.8;
    private static final double LETHAL_MIN_HORIZONTAL_SPEED = 1.10;

    private final SimpleFactionsRaidingPlugin plugin;
    private final CustomTNTManager customTNTManager;
    private final Random random = new Random();
    private final List<PendingPrime> pendingPrimes = new ArrayList<>();

    public CustomExplosiveListener(SimpleFactionsRaidingPlugin plugin, CustomTNTManager customTNTManager) {
        this.plugin = plugin;
        this.customTNTManager = customTNTManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceCustomTNT(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.TNT) {
            return;
        }

        CustomTNTManager.CustomTNTType type = customTNTManager.identifyTNTItem(event.getItemInHand());
        if (type == null) {
            return;
        }

        event.getBlockPlaced().setMetadata(BLOCK_META_KEY, new FixedMetadataValue(plugin, type.name()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTNTPrime(TNTPrimeEvent event) {
        Block block = event.getBlock();
        if (block == null || block.getType() != Material.TNT) {
            return;
        }

        CustomTNTManager.CustomTNTType type = null;
        if (block.hasMetadata(BLOCK_META_KEY)) {
            try {
                String value = block.getMetadata(BLOCK_META_KEY).get(0).asString();
                type = CustomTNTManager.CustomTNTType.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }

        if (type == null) {
            return;
        }

        pendingPrimes.add(new PendingPrime(block.getLocation(), type, System.currentTimeMillis() + PRIME_MATCH_TTL_MS));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed primed)) {
            return;
        }

        cleanupPending();
        PendingPrime match = findBestMatch(primed.getLocation());
        if (match != null) {
            customTNTManager.registerCustomTNT(primed, match.type);
            pendingPrimes.remove(match);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenseCustomTNT(BlockDispenseEvent event) {
        CustomTNTManager.CustomTNTType type = customTNTManager.identifyTNTItem(event.getItem());
        if (type == null) {
            return;
        }

        event.setCancelled(true);

        Block dispenser = event.getBlock();
        Location spawnLoc = dispenser.getLocation().add(0.5, 0.5, 0.5);
        if (dispenser.getBlockData() instanceof org.bukkit.block.data.Directional directional) {
            spawnLoc = dispenser.getRelative(directional.getFacing()).getLocation().add(0.5, 0.5, 0.5);
        }

        TNTPrimed tnt = dispenser.getWorld().spawn(spawnLoc, TNTPrimed.class, spawned -> {
            spawned.setFuseTicks(plugin.getConfig().getInt("cannoning.tnt-fuse-ticks", 80));
            spawned.setVelocity(new Vector(0, 0, 0));
        });
        customTNTManager.registerCustomTNT(tnt, type);

        removeOneMatchingItem(dispenser, event.getItem());
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseCustomCreeperEgg(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack inHand = event.getItem();
        CustomTNTManager.CustomCreeperType type = customTNTManager.identifyCreeperEggItem(inHand);
        if (type == null) {
            return;
        }

        event.setCancelled(true);

        Location spawnLoc;
        if (event.getClickedBlock() != null) {
            spawnLoc = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        } else {
            spawnLoc = event.getPlayer().getLocation().add(event.getPlayer().getLocation().getDirection().normalize());
        }

        Creeper creeper = event.getPlayer().getWorld().spawn(spawnLoc, Creeper.class);
        customTNTManager.registerCustomCreeper(creeper, type);

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE && inHand != null) {
            if (inHand.getAmount() <= 1) {
                event.getPlayer().getInventory().setItemInMainHand(null);
            } else {
                inHand.setAmount(inHand.getAmount() - 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof TNTPrimed tnt) {
            CustomTNTManager.CustomTNTType tntType = customTNTManager.getCustomTNTType(tnt);
            if (tntType != null) {
                event.setRadius((float) customTNTManager.getExplosionRadius(tntType));
            }
            return;
        }

        if (entity instanceof Creeper creeper) {
            CustomTNTManager.CustomCreeperType creeperType = customTNTManager.getCustomCreeperType(creeper);
            if (creeperType != null) {
                event.setRadius((float) customTNTManager.getExplosionRadius(creeperType));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();

        CustomTNTManager.CustomTNTType tntType = entity instanceof TNTPrimed t ? customTNTManager.getCustomTNTType(t) : null;
        CustomTNTManager.CustomCreeperType creeperType = entity instanceof Creeper c ? customTNTManager.getCustomCreeperType(c) : null;

        if (!(entity instanceof TNTPrimed) && !(entity instanceof Creeper)) {
            return;
        }

        if (tntType != null || creeperType != null) {
            amplifyBlockDamage(event, tntType, creeperType);
        }

        if (tntType == CustomTNTManager.CustomTNTType.LETHAL) {
            amplifyLethalTntProjection(event);
        }

        double spawnerDropChance = 0.50;
        if (tntType != null) {
            spawnerDropChance = customTNTManager.getSpawnerDropChance(tntType);
        } else if (creeperType != null) {
            spawnerDropChance = customTNTManager.getSpawnerDropChance(creeperType);
        }

        processSpawnerDrops(event, spawnerDropChance);
    }

    private void amplifyLethalTntProjection(EntityExplodeEvent event) {
        Location center = event.getLocation();
        if (center.getWorld() == null) {
            return;
        }

        Map<TNTPrimed, Vector> preExplosionVelocities = new HashMap<>();
        for (Entity nearby : center.getNearbyEntities(
                LETHAL_PROJECTION_RADIUS,
                LETHAL_PROJECTION_RADIUS,
                LETHAL_PROJECTION_RADIUS)) {
            if (!(nearby instanceof TNTPrimed primed)) {
                continue;
            }
            if (!primed.isValid()) {
                continue;
            }
            if (primed.getUniqueId().equals(event.getEntity().getUniqueId())) {
                continue;
            }
            preExplosionVelocities.put(primed, primed.getVelocity().clone());
        }

        if (preExplosionVelocities.isEmpty()) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<TNTPrimed, Vector> entry : preExplosionVelocities.entrySet()) {
                TNTPrimed primed = entry.getKey();
                if (!primed.isValid()) {
                    continue;
                }

                Vector before = entry.getValue();
                Vector after = primed.getVelocity();
                Vector explosionDelta = after.clone().subtract(before);

                if (explosionDelta.lengthSquared() < 1.0e-4) {
                    continue;
                }

                Vector boostedDelta = explosionDelta.clone().multiply(LETHAL_PROJECTION_MULTIPLIER);
                Vector boostedVelocity = before.clone().add(boostedDelta);

                double horizontal = Math.hypot(boostedVelocity.getX(), boostedVelocity.getZ());
                if (horizontal > 1.0e-6 && horizontal < LETHAL_MIN_HORIZONTAL_SPEED) {
                    double scale = LETHAL_MIN_HORIZONTAL_SPEED / horizontal;
                    boostedVelocity.setX(boostedVelocity.getX() * scale);
                    boostedVelocity.setZ(boostedVelocity.getZ() * scale);
                }

                primed.setVelocity(boostedVelocity);
            }
        }, 1L);
    }

    private void amplifyBlockDamage(EntityExplodeEvent event,
                                    CustomTNTManager.CustomTNTType tntType,
                                    CustomTNTManager.CustomCreeperType creeperType) {
        double radius = event.getYield();
        if (radius <= 0) {
            radius = tntType != null
                    ? customTNTManager.getExplosionRadius(tntType)
                    : customTNTManager.getExplosionRadius(creeperType);
        }

        double damageMultiplier = 1.0;
        if (tntType != null) {
            damageMultiplier = customTNTManager.getDamageMultiplier(tntType);
        } else if (creeperType != null) {
            damageMultiplier = customTNTManager.getDamageMultiplier(creeperType);
        }

        int scanRadius = (int) Math.ceil(radius);
        Location origin = event.getLocation();
        var world = origin.getWorld();
        if (world == null) {
            return;
        }

        Map<Block, Boolean> additions = new HashMap<>();
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int y = -scanRadius; y <= scanRadius; y++) {
                for (int z = -scanRadius; z <= scanRadius; z++) {
                    double distSq = x * x + y * y + z * z;
                    if (distSq > radius * radius) {
                        continue;
                    }

                    Block block = world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                    if (!canExplosionTouch(block.getType())) {
                        continue;
                    }

                    if (isObsidian(block.getType())) {
                        double breakChance = switch (String.valueOf(getVariantName(tntType, creeperType))) {
                            case "LETHAL" -> 0.65;
                            case "GIGANTIC" -> 0.95;
                            case "LUCKY" -> 0.50;
                            default -> 0.35;
                        };

                        if (random.nextDouble() <= breakChance) {
                            additions.put(block, true);
                        }
                        continue;
                    }

                    if (damageMultiplier <= 1.0) {
                        continue;
                    }

                    double chance = Math.min(1.0, 0.25 * damageMultiplier);
                    if (random.nextDouble() <= chance) {
                        additions.put(block, true);
                    }
                }
            }
        }

        for (Block block : additions.keySet()) {
            if (!event.blockList().contains(block)) {
                event.blockList().add(block);
            }
        }
    }

    private void processSpawnerDrops(EntityExplodeEvent event, double dropChance) {
        List<Block> affected = new ArrayList<>(event.blockList());
        for (Block block : affected) {
            if (block.getType() != Material.SPAWNER) {
                continue;
            }

            event.blockList().remove(block);

            String mobName = "Unknown";
            if (block.getState() instanceof CreatureSpawner spawner) {
                mobName = prettyMob(spawner.getSpawnedType().name());
            }

            block.setType(Material.AIR);
            if (random.nextDouble() <= dropChance) {
                ItemStack droppedSpawner = new ItemStack(Material.SPAWNER, 1);
                ItemMeta meta = droppedSpawner.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§e" + mobName + " Spawner");
                    meta.setLore(List.of("§7Dropped from explosion", "§7Chance: §f" + (int) Math.round(dropChance * 100) + "%"));
                    droppedSpawner.setItemMeta(meta);
                }
                event.getLocation().getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), droppedSpawner);
            }
        }
    }

    private void removeOneMatchingItem(Block dispenserBlock, ItemStack expected) {
        if (!(dispenserBlock.getState() instanceof org.bukkit.block.Dispenser dispenser)) {
            return;
        }

        ItemStack[] contents = dispenser.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            if (stack.getType() != expected.getType()) continue;
            if (!isSameExplosive(stack, expected)) continue;

            if (stack.getAmount() <= 1) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            dispenser.getInventory().setContents(contents);
            dispenser.update(true, false);
            return;
        }
    }

    private boolean isSameExplosive(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        if (!a.hasItemMeta() || !b.hasItemMeta()) return false;
        String da = a.getItemMeta().getDisplayName();
        String db = b.getItemMeta().getDisplayName();
        return da != null && da.equals(db);
    }

    private String getVariantName(CustomTNTManager.CustomTNTType tntType, CustomTNTManager.CustomCreeperType creeperType) {
        if (tntType != null) return tntType.name();
        if (creeperType != null) return creeperType.name();
        return "VANILLA";
    }

    private boolean canExplosionTouch(Material material) {
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) return false;
        return switch (material) {
            case BEDROCK, BARRIER, END_PORTAL_FRAME, END_PORTAL, COMMAND_BLOCK,
                    CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK -> false;
            default -> true;
        };
    }

    private boolean isObsidian(Material material) {
        return material == Material.OBSIDIAN || material == Material.CRYING_OBSIDIAN;
    }

    private String prettyMob(String name) {
        String[] parts = name.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private void cleanupPending() {
        long now = System.currentTimeMillis();
        Iterator<PendingPrime> it = pendingPrimes.iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt < now) {
                it.remove();
            }
        }
    }

    private PendingPrime findBestMatch(Location location) {
        PendingPrime best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PendingPrime pending : pendingPrimes) {
            if (!pending.location.getWorld().equals(location.getWorld())) {
                continue;
            }
            double distSq = pending.location.distanceSquared(location);
            if (distSq > 4.0) {
                continue;
            }
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pending;
            }
        }
        return best;
    }

    private static class PendingPrime {
        private final Location location;
        private final CustomTNTManager.CustomTNTType type;
        private final long expiresAt;

        private PendingPrime(Location location, CustomTNTManager.CustomTNTType type, long expiresAt) {
            this.location = location;
            this.type = type;
            this.expiresAt = expiresAt;
        }
    }
}
