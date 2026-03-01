package local.simplefactionsraiding;

import local.simplefactions.FactionManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Handles /f tnt subcommands.
 */
public class FactionTntCommandListener implements Listener {

    private final SimpleFactionsRaidingPlugin plugin;
    private final FactionManager factionManager;
    private final int maxRadius;

    public FactionTntCommandListener(SimpleFactionsRaidingPlugin plugin, FactionManager factionManager) {
        this.plugin = plugin;
        this.factionManager = factionManager;
        this.maxRadius = Math.max(4, plugin.getConfig().getInt("performance.tnt-command-max-radius", 32));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return;
        }

        String trimmed = message.trim();
        if (!startsWithFactionTntCommand(trimmed)) {
            return;
        }

        event.setCancelled(true);

        String noSlash = trimmed.substring(1);
        String[] parts = noSlash.split("\\s+");

        Player player = event.getPlayer();
        FactionManager.Faction faction = factionManager.getFactionByPlayer(player);
        if (faction == null) {
            player.sendMessage("§cYou must be in a faction to use TNT bank commands.");
            return;
        }

        if (parts.length < 2) {
            sendHelp(player);
            return;
        }

        if (parts.length == 2) {
            sendHelp(player);
            return;
        }

        String sub = parts[2].toLowerCase(Locale.ROOT);
        List<String> args = parts.length > 3 ? Arrays.asList(parts).subList(3, parts.length) : List.of();

        switch (sub) {
            case "deposit":
            case "d":
                handleDeposit(player, faction, args);
                break;
            case "withdraw":
            case "w":
                handleWithdraw(player, faction, args);
                break;
            case "bal":
            case "b":
                handleBalance(player, faction);
                break;
            case "fill":
            case "f":
                handleFill(player, faction, args);
                break;
            case "siphon":
            case "s":
                handleSiphon(player, faction, args);
                break;
            default:
                sendHelp(player);
                break;
        }
    }

    private boolean startsWithFactionTntCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.startsWith("/f tnt") || lower.startsWith("/factions tnt");
    }

    private void handleDeposit(Player player, FactionManager.Faction faction, List<String> args) {
        int available = countMaterial(player.getInventory(), Material.TNT);
        if (available <= 0) {
            player.sendMessage("§cYou have no TNT in your inventory.");
            return;
        }

        int amount = available;
        if (!args.isEmpty()) {
            Integer parsed = parseAmountArg(args.get(0), available);
            if (parsed == null) {
                player.sendMessage("§cUsage: /f tnt deposit (d) [amount|all]");
                return;
            }
            amount = Math.min(parsed, available);
        }

        int removed = removeMaterial(player.getInventory(), Material.TNT, amount);
        if (removed <= 0) {
            player.sendMessage("§cCould not deposit TNT from your inventory.");
            return;
        }

        faction.addTnt(removed);
        player.sendMessage("§aDeposited §e" + removed + " TNT §ato faction TNT bank.");
        showBalanceLine(player, faction);
    }

    private void handleWithdraw(Player player, FactionManager.Faction faction, List<String> args) {
        int balance = Math.max(0, faction.getTntBank());
        if (balance <= 0) {
            player.sendMessage("§cFaction TNT bank is empty.");
            return;
        }

        int requested;
        if (args.isEmpty()) {
            requested = Math.min(64, balance);
        } else {
            Integer parsed = parseAmountArg(args.get(0), balance);
            if (parsed == null) {
                player.sendMessage("§cUsage: /f tnt withdraw (w) [amount|all]");
                return;
            }
            requested = Math.min(parsed, balance);
        }

        int moved = addMaterial(player.getInventory(), Material.TNT, requested);
        if (moved <= 0) {
            player.sendMessage("§cYour inventory is full.");
            return;
        }

        faction.removeTnt(moved);
        player.sendMessage("§aWithdrew §e" + moved + " TNT §afrom faction TNT bank.");
        showBalanceLine(player, faction);
    }

    private void handleBalance(Player player, FactionManager.Faction faction) {
        showBalanceLine(player, faction);
    }

    private void handleFill(Player player, FactionManager.Faction faction, List<String> args) {
        if (args.size() < 3) {
            player.sendMessage("§cUsage: /f tnt fill (f) <radius> <amountPerDispenser> <maxPerDispenser>");
            return;
        }

        Integer radiusParsed = parsePositiveInt(args.get(0));
        Integer amountPerDispenser = parsePositiveInt(args.get(1));
        Integer maxPerDispenser = parsePositiveInt(args.get(2));

        if (radiusParsed == null || amountPerDispenser == null || maxPerDispenser == null) {
            player.sendMessage("§cRadius and amounts must be positive integers.");
            return;
        }

        int radius = Math.min(radiusParsed, maxRadius);
        if (amountPerDispenser > maxPerDispenser) {
            player.sendMessage("§cAmount per dispenser cannot exceed max per dispenser.");
            return;
        }

        int bank = Math.max(0, faction.getTntBank());
        if (bank <= 0) {
            player.sendMessage("§cFaction TNT bank is empty.");
            return;
        }

        List<Container> containers = findTntContainersInRadius(player, faction, radius);
        if (containers.isEmpty()) {
            player.sendMessage("§cNo claimed dispensers/droppers found in radius " + radius + ".");
            return;
        }

        int totalAdded = 0;
        int touched = 0;

        for (Container container : containers) {
            Container liveContainer = resolveLiveContainer(container);
            if (liveContainer == null) {
                continue;
            }

            if (bank <= 0) {
                break;
            }

            int current = countLiveMaterial(liveContainer.getBlock(), Material.TNT);
            if (current >= maxPerDispenser) {
                continue;
            }

            int roomByCommandCap = maxPerDispenser - current;
            int targetAdd = Math.min(amountPerDispenser, roomByCommandCap);
            int addNow = Math.min(targetAdd, bank);
            int verifiedAdded = verifiedAddTnt(liveContainer, addNow);
            if (verifiedAdded > 0) {
                bank -= verifiedAdded;
                totalAdded += verifiedAdded;
                touched++;
            }
        }

        if (totalAdded <= 0) {
            player.sendMessage("§cNo TNT was added. Dispensers may already be full or blocked by inventory layout.");
            return;
        }

        faction.setTntBank(bank);

        player.sendMessage("§aFilled §e" + touched + " dispensers §awith §e" + totalAdded + " TNT §a(radius " + radius + ").");
        showBalanceLine(player, faction);
    }

    private void handleSiphon(Player player, FactionManager.Faction faction, List<String> args) {
        if (args.isEmpty()) {
            player.sendMessage("§cUsage: /f tnt siphon (s) <amountPerDispenser|all> [radius]");
            return;
        }

        String amountArg = args.get(0).toLowerCase(Locale.ROOT);
        boolean siphonAll = amountArg.equals("all");
        Integer amountPerDispenser = siphonAll ? null : parsePositiveInt(amountArg);
        if (!siphonAll && amountPerDispenser == null) {
            player.sendMessage("§cUsage: /f tnt siphon (s) <amountPerDispenser|all> [radius]");
            return;
        }

        int radius = Math.max(1, Math.min(maxRadius,
                args.size() >= 2 && parsePositiveInt(args.get(1)) != null ? parsePositiveInt(args.get(1)) :
                        plugin.getConfig().getInt("performance.tnt-command-default-radius", 16)));

        List<Container> containers = findTntContainersInRadius(player, faction, radius);
        if (containers.isEmpty()) {
            player.sendMessage("§cNo claimed dispensers/droppers found in radius " + radius + ".");
            return;
        }

        int totalRemoved = 0;
        int touched = 0;

        for (Container container : containers) {
            Container liveContainer = resolveLiveContainer(container);
            if (liveContainer == null) {
                continue;
            }

            int current = countLiveMaterial(liveContainer.getBlock(), Material.TNT);
            if (current <= 0) {
                continue;
            }

            int toTake = siphonAll ? current : Math.min(amountPerDispenser, current);
            int verifiedRemoved = verifiedRemoveTnt(liveContainer, toTake);
            if (verifiedRemoved > 0) {
                totalRemoved += verifiedRemoved;
                touched++;
            }
        }

        if (totalRemoved <= 0) {
            player.sendMessage("§cNo TNT could be siphoned from nearby dispensers.");
            return;
        }

        faction.addTnt(totalRemoved);

        player.sendMessage("§aSiphoned §e" + totalRemoved + " TNT §afrom §e" + touched + " dispensers §a(radius " + radius + ").");
        showBalanceLine(player, faction);
    }

    private void showBalanceLine(Player player, FactionManager.Faction faction) {
        int balance = Math.max(0, faction.getTntBank());
        int stacks = balance / 64;
        int singles = balance % 64;
        player.sendMessage("§7Faction TNT Bank: §e" + balance + " TNT §7(" + stacks + " stacks + " + singles + " TNT)");
    }

    private List<Container> findTntContainersInRadius(Player player, FactionManager.Faction playerFaction, int radius) {
        List<Container> containers = new ArrayList<>();

        World world = player.getWorld();
        int centerX = player.getLocation().getBlockX();
        int centerY = player.getLocation().getBlockY();
        int centerZ = player.getLocation().getBlockZ();

        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minY = Math.max(world.getMinHeight(), centerY - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + radius);
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;

        double radiusSq = (double) radius * radius;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    double distanceSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distanceSq > radiusSq) {
                        continue;
                    }

                    int chunkX = Math.floorDiv(x, 16);
                    int chunkZ = Math.floorDiv(z, 16);
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type != Material.DISPENSER && type != Material.DROPPER) {
                        continue;
                    }

                    FactionManager.Faction owner = factionManager.getFactionByChunk(
                            world.getName(),
                            chunkX,
                            chunkZ
                    );
                    if (owner == null) {
                        continue;
                    }
                    if (playerFaction == null || !owner.getName().equalsIgnoreCase(playerFaction.getName())) {
                        continue;
                    }

                    BlockState state = block.getState();
                    if (state instanceof Container container) {
                        containers.add(container);
                    }
                }
            }
        }

        return containers;
    }

    private Integer parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseAmountArg(String raw, int maxForAll) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.equals("all")) {
            return maxForAll;
        }
        return parsePositiveInt(normalized);
    }

    private int countMaterial(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private int removeMaterial(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }

            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= take;
        }

        inventory.setContents(contents);
        return amount - remaining;
    }

    private int addMaterial(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();

        // Fill existing stacks first
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }

            int room = 64 - stack.getAmount();
            if (room <= 0) {
                continue;
            }

            int put = Math.min(room, remaining);
            stack.setAmount(stack.getAmount() + put);
            remaining -= put;
        }

        // Fill empty slots
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (contents[i] != null) {
                continue;
            }

            int put = Math.min(64, remaining);
            contents[i] = new ItemStack(material, put);
            remaining -= put;
        }

        inventory.setContents(contents);
        return amount - remaining;
    }

    private int verifiedAddTnt(Container container, int amount) {
        Block block = container.getBlock();
        Inventory inventory = getLiveInventory(block);
        if (inventory == null) {
            return 0;
        }

        int before = countMaterial(inventory, Material.TNT);
        int attempted = addMaterial(inventory, Material.TNT, amount);
        if (attempted <= 0) {
            return 0;
        }

        int after = countLiveMaterial(block, Material.TNT);
        int verified = Math.max(0, after - before);
        if (verified > 0) {
            return verified;
        }

        BlockState state = block.getState();
        if (state instanceof Container fallbackContainer) {
            Inventory snapshot = fallbackContainer.getSnapshotInventory();
            int snapAttempted = addMaterial(snapshot, Material.TNT, amount);
            if (snapAttempted <= 0) {
                return 0;
            }
            fallbackContainer.update(true, false);
            int fallbackAfter = countLiveMaterial(block, Material.TNT);
            return Math.max(0, fallbackAfter - before);
        }

        return 0;
    }

    private int verifiedRemoveTnt(Container container, int amount) {
        Block block = container.getBlock();
        Inventory inventory = getLiveInventory(block);
        if (inventory == null) {
            return 0;
        }

        int before = countMaterial(inventory, Material.TNT);
        int attempted = removeMaterial(inventory, Material.TNT, amount);
        if (attempted <= 0) {
            return 0;
        }

        int after = countLiveMaterial(block, Material.TNT);
        int verified = Math.max(0, before - after);
        if (verified > 0) {
            return verified;
        }

        BlockState state = block.getState();
        if (state instanceof Container fallbackContainer) {
            Inventory snapshot = fallbackContainer.getSnapshotInventory();
            int snapAttempted = removeMaterial(snapshot, Material.TNT, amount);
            if (snapAttempted <= 0) {
                return 0;
            }
            fallbackContainer.update(true, false);
            int fallbackAfter = countLiveMaterial(block, Material.TNT);
            return Math.max(0, before - fallbackAfter);
        }

        return 0;
    }

    private int countLiveMaterial(Block block, Material material) {
        Inventory inventory = getLiveInventory(block);
        if (inventory == null) {
            return 0;
        }
        return countMaterial(inventory, material);
    }

    private Inventory getLiveInventory(Block block) {
        BlockState state = block.getState();
        if (state instanceof InventoryHolder inventoryHolder) {
            return inventoryHolder.getInventory();
        }
        return null;
    }

    private Container resolveLiveContainer(Container scannedState) {
        BlockState liveState = scannedState.getBlock().getState();
        if (liveState instanceof Container container) {
            return container;
        }
        return null;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== Faction TNT Commands ===");
        player.sendMessage("§e/f tnt deposit (d) [amount|all]");
        player.sendMessage("§e/f tnt withdraw (w) [amount|all]");
        player.sendMessage("§e/f tnt bal (b)");
        player.sendMessage("§e/f tnt fill (f) <radius> <amountPerDispenser> <maxPerDispenser>");
        player.sendMessage("§e/f tnt siphon (s) <amountPerDispenser|all> [radius]");
    }
}
