package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SimpleFactionsRaidingTabCompleter implements TabCompleter {

    private final CoreChunkManager coreChunkManager;

    public SimpleFactionsRaidingTabCompleter(CoreChunkManager coreChunkManager) {
        this.coreChunkManager = coreChunkManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        return switch (cmd) {
            case "raid" -> completeRaid(sender, args);
            case "customtnt", "customcreeper" -> completeCustomExplosive(sender, args);
            case "createcollectionchest" -> completeCreateCollectionChest(sender, args);
            case "server" -> completeServer(args);
            case "worldreset" -> completeWorldReset(sender, args);
            case "restartnow", "rebootforce", "forcereboot", "forcerestart", "reboot", "collectionfilter", "wild", "widlerness", "hub", "spawn", "setspawn" -> List.of();
            default -> List.of();
        };
    }

    private List<String> completeRaid(CommandSender sender, String[] args) {
        boolean admin = hasRaidAdmin(sender);

        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("status", "info", "points"));
            if (admin) {
                out.add("start");
                out.add("stop");
            }
            return filter(out, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("start") || sub.equals("stop")) && !admin) {
                return List.of();
            }
            if (sub.equals("status") || sub.equals("info") || sub.equals("points") || sub.equals("start") || sub.equals("stop")) {
                return filter(factionNames(), args[1]);
            }
        }

        return List.of();
    }

    private List<String> completeCustomExplosive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.customexplosive") && !sender.isOp()) {
            return List.of();
        }

        if (args.length == 1) return filter(List.of("give"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return filter(onlinePlayers(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(List.of("lethal", "gigantic", "lucky", "random"), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return filter(List.of("1", "2", "5", "10", "16", "32", "64"), args[3]);

        return List.of();
    }

    private List<String> completeCreateCollectionChest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.createcollectionchest") && !sender.isOp()) {
            return List.of();
        }
        if (args.length == 1) return filter(List.of("chest", "trapped"), args[0]);
        return List.of();
    }

    private List<String> completeServer(String[] args) {
        if (args.length == 1) return filter(List.of("factions"), args[0]);
        return List.of();
    }

    private List<String> completeWorldReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.worldreset") && !sender.isOp()) {
            return List.of();
        }
        if (args.length == 1) return filter(List.of("spawn", "nether", "end"), args[0]);
        if (args.length == 2) return filter(List.of("confirm"), args[1]);
        return List.of();
    }

    private boolean hasRaidAdmin(CommandSender sender) {
        return sender.hasPermission("simplefactionsraiding.admin") || sender.isOp();
    }

    private List<String> factionNames() {
        List<String> out = new ArrayList<>();
        for (CoreChunkManager.ChunkData chunk : coreChunkManager.getAllCoreChunks()) {
            if (chunk.factionName != null && !chunk.factionName.isBlank()) {
                out.add(chunk.factionName);
            }
        }
        out = out.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        return out;
    }

    private List<String> onlinePlayers() {
        List<String> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            out.add(player.getName());
        }
        return out;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
