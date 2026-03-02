package local.simplefactionsraiding;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command handler for /collectionfilter and related collection chest commands.
 */
public class CollectionChestCommand implements CommandExecutor {

    private final CollectionChestManager chestManager;
    private final CollectionFilterGUI filterGUI;

    public CollectionChestCommand(CollectionChestManager chestManager, CollectionFilterGUI filterGUI) {
        this.chestManager = chestManager;
        this.filterGUI = filterGUI;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("collectionfilter")) {
            return handleCollectionFilterCommand(player, args);
        } else if (command.getName().equalsIgnoreCase("createcollectionchest")) {
            return handleCreateCommand(player, args);
        }

        return false;
    }

    private boolean handleCollectionFilterCommand(Player player, String[] args) {
        // Check if player is looking at a collection chest
        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !chestManager.isCollectionChest(targetBlock)) {
            player.sendMessage("§cYou must be looking at a collection chest within 5 blocks.");
            return true;
        }

        filterGUI.openMainFilterMenu(player, targetBlock);
        player.sendMessage("§a✓ Opened collection chest filter menu.");
        return true;
    }

    private boolean handleCreateCommand(Player player, String[] args) {
        if (!player.hasPermission("simplefactionsraiding.admin.createcollectionchest")) {
            player.sendMessage("§cYou don't have permission to create collection chests.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cUsage: /createcollectionchest <chest|trapped>");
            return true;
        }

        String type = args[0].toLowerCase();
        boolean trapped = type.equals("trapped");

        if (!type.equals("chest") && !type.equals("trapped")) {
            player.sendMessage("§cInvalid type. Use 'chest' or 'trapped'.");
            return true;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Container)) {
            player.sendMessage("§cYou must be looking at a chest or trapped chest within 5 blocks.");
            return true;
        }

        chestManager.createCollectionChest(targetBlock, trapped);
        String typeStr = trapped ? "Trapped Collection" : "Collection";
        player.sendMessage("§a✓ Created " + typeStr + " Chest at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ());
        return true;
    }
}
