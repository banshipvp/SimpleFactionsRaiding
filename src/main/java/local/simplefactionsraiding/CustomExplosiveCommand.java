package local.simplefactionsraiding;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Random;

public class CustomExplosiveCommand implements CommandExecutor {

    public enum CommandKind {
        TNT,
        CREEPER
    }

    private final CustomTNTManager customTNTManager;
    private final CommandKind kind;
    private final Random random = new Random();

    public CustomExplosiveCommand(CustomTNTManager customTNTManager, CommandKind kind) {
        this.customTNTManager = customTNTManager;
        this.kind = kind;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("simplefactionsraiding.admin.customexplosive")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sendUsage(sender, label);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found or offline: §f" + args[1]);
            return true;
        }

        String typeArg = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "random";
        int amount = 1;
        if (args.length >= 4) {
            Integer parsed = parsePositiveInt(args[3]);
            if (parsed == null) {
                sender.sendMessage("§cAmount must be a positive number.");
                return true;
            }
            amount = Math.min(parsed, 64);
        }

        ItemStack item;
        String resolvedType;

        if (kind == CommandKind.TNT) {
            CustomTNTManager.CustomTNTType type = parseTntType(typeArg);
            if (type == null) {
                sender.sendMessage("§cInvalid TNT type. Use: lethal, gigantic, lucky, random");
                return true;
            }
            item = customTNTManager.createCustomTNTItem(type, amount);
            resolvedType = type.name().toLowerCase(Locale.ROOT);
        } else {
            CustomTNTManager.CustomCreeperType type = parseCreeperType(typeArg);
            if (type == null) {
                sender.sendMessage("§cInvalid creeper type. Use: lethal, gigantic, lucky, random");
                return true;
            }
            item = customTNTManager.createCustomCreeperEggItem(type, amount);
            resolvedType = type.name().toLowerCase(Locale.ROOT);
        }

        target.getInventory().addItem(item).values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));

        sender.sendMessage("§aGave §e" + amount + "x §f" + resolvedType + " §ato §e" + target.getName());
        target.sendMessage("§aYou received custom " + (kind == CommandKind.TNT ? "TNT" : "creeper eggs") + " from §e" + sender.getName());
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        if (kind == CommandKind.TNT) {
            sender.sendMessage("§cUsage: /" + label + " give <player> [lethal|gigantic|lucky|random] [amount]");
        } else {
            sender.sendMessage("§cUsage: /" + label + " give <player> [lethal|gigantic|lucky|random] [amount]");
        }
    }

    private CustomTNTManager.CustomTNTType parseTntType(String value) {
        if (value.equals("random")) {
            CustomTNTManager.CustomTNTType[] values = CustomTNTManager.CustomTNTType.values();
            return values[random.nextInt(values.length)];
        }
        return switch (value) {
            case "lethal" -> CustomTNTManager.CustomTNTType.LETHAL;
            case "gigantic" -> CustomTNTManager.CustomTNTType.GIGANTIC;
            case "lucky" -> CustomTNTManager.CustomTNTType.LUCKY;
            default -> null;
        };
    }

    private CustomTNTManager.CustomCreeperType parseCreeperType(String value) {
        if (value.equals("random")) {
            CustomTNTManager.CustomCreeperType[] values = CustomTNTManager.CustomCreeperType.values();
            return values[random.nextInt(values.length)];
        }
        return switch (value) {
            case "lethal" -> CustomTNTManager.CustomCreeperType.LETHAL;
            case "gigantic" -> CustomTNTManager.CustomCreeperType.GIGANTIC;
            case "lucky" -> CustomTNTManager.CustomCreeperType.LUCKY;
            default -> null;
        };
    }

    private Integer parsePositiveInt(String input) {
        try {
            int value = Integer.parseInt(input);
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
