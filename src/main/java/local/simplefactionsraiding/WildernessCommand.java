package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WildernessCommand implements CommandExecutor {

    private final SimpleFactionsRaidingPlugin plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public WildernessCommand(SimpleFactionsRaidingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        int cooldownSeconds = Math.max(1, plugin.getConfig().getInt("wilderness.cooldown-seconds", 60));
        if (!player.hasPermission("simplefactionsraiding.wild.bypasscooldown")) {
            long now = System.currentTimeMillis();
            long nextAllowed = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (nextAllowed > now) {
                long left = (long) Math.ceil((nextAllowed - now) / 1000.0);
                player.sendMessage("§cYou must wait §e" + left + "s §cbefore using /" + label.toLowerCase(Locale.ROOT) + " again.");
                return true;
            }
        }

        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage("§cYou can only use this command in the overworld.");
            return true;
        }

        int borderSize = Math.max(100, plugin.getConfig().getInt("wilderness.world-border-size", 5000));
        int half = borderSize / 2;
        int padding = Math.max(16, plugin.getConfig().getInt("wilderness.border-padding", 32));
        int min = -half + padding;
        int max = half - padding;

        int maxAttempts = Math.max(20, plugin.getConfig().getInt("wilderness.max-attempts", 120));
        player.sendMessage("§7Teleporting to wilderness...");
        findAndTeleportAsync(player, world, min, max, maxAttempts, cooldownSeconds, label);

        return true;
    }

    private void findAndTeleportAsync(Player player,
                                      World world,
                                      int min,
                                      int max,
                                      int remainingAttempts,
                                      int cooldownSeconds,
                                      String label) {
        if (!player.isOnline()) {
            return;
        }

        if (remainingAttempts <= 0) {
            player.sendMessage("§cCould not find a safe wilderness location. Try again.");
            return;
        }

        int x = randomInRange(min, max);
        int z = randomInRange(min, max);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        world.getChunkAtAsync(chunkX, chunkZ, true, true).whenComplete((Chunk chunk, Throwable throwable) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }

                if (throwable != null || chunk == null) {
                    findAndTeleportAsync(player, world, min, max, remainingAttempts - 1, cooldownSeconds, label);
                    return;
                }

                int y = world.getHighestBlockYAt(x, z);
                if (y <= world.getMinHeight()) {
                    findAndTeleportAsync(player, world, min, max, remainingAttempts - 1, cooldownSeconds, label);
                    return;
                }

                Block ground = world.getBlockAt(x, y - 1, z);
                Material groundType = ground.getType();
                if (!groundType.isSolid() || isUnsafeGround(groundType)) {
                    findAndTeleportAsync(player, world, min, max, remainingAttempts - 1, cooldownSeconds, label);
                    return;
                }

                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!feet.isPassable() || !head.isPassable()) {
                    findAndTeleportAsync(player, world, min, max, remainingAttempts - 1, cooldownSeconds, label);
                    return;
                }

                Location destination = new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
                player.teleportAsync(destination).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (Boolean.TRUE.equals(success)) {
                        if (!player.hasPermission("simplefactionsraiding.wild.bypasscooldown")) {
                            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
                        }
                        player.sendMessage("§aTeleported to wilderness at §e" + destination.getBlockX() + "§7, §e" + destination.getBlockY() + "§7, §e" + destination.getBlockZ());
                    } else {
                        player.sendMessage("§cTeleport failed. Please try again.");
                    }
                }));
            });
        });
    }

    private boolean isUnsafeGround(Material material) {
        return material == Material.LAVA
                || material == Material.MAGMA_BLOCK
                || material == Material.CACTUS
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || material == Material.WATER;
    }

    private int randomInRange(int min, int max) {
        if (max <= min) {
            return min;
        }
        return random.nextInt(max - min + 1) + min;
    }
}
