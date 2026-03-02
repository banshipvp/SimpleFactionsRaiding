package local.simplefactionsraiding;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.util.SideEffectSet;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pastecannon [schematic] [-a]
 *
 * Loads a schematic from plugins/WorldEdit/schematics/ and instantly pastes
 * it at the player's current location, rotated to match their facing direction.
 *
 * Flags:
 *   -a   Ignore air blocks (paste only solid blocks, preserving existing terrain)
 *
 * With no arguments, lists all available schematics.
 */
public class PasteCannonCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    // Per-player undo/redo stacks — each entry is a snapshot (clipboard or changed blocks)
    private final Map<UUID, Deque<UndoSnapshot>> undoHistory = new HashMap<>();
    private final Map<UUID, Deque<UndoSnapshot>> redoHistory = new HashMap<>();
    private final Map<UUID, ActivePaste> activePastes = new HashMap<>();
    private static final int MAX_HISTORY = 5;

    /** Blocks placed per server tick during a batched paste/undo/redo. */
    private static final int BLOCKS_PER_TICK = 17_000;
    /** Max clipboard positions scanned per tick while streaming a large paste. */
    private static final int MAX_SCANNED_PER_TICK = 200_000;
    /** Skip undo snapshot if target region exceeds this many blocks to avoid server hangs. */
    private static final long MAX_UNDO_SNAPSHOT_BLOCKS = 100_000L;
    /** Use material-only block conversion above this size to maximize compatibility and completion. */
    private static final long MATERIAL_ONLY_PASTE_THRESHOLD = 250_000L;
    /** Chunks loaded per tick during preloading step for large pastes. */
    private static final int CHUNKS_PER_TICK = 10;

    /** Lightweight record holding a pending block placement. */
    private record PendingBlock(int x, int y, int z, org.bukkit.block.data.BlockData data) {}
    /** Lightweight record for chunk coordinates. */
    private record ChunkCoord(int x, int z) {}
    /** Snapshot of a placed block for cancel rollback. */
    private record ChangedBlock(int x, int y, int z, org.bukkit.block.data.BlockData oldData) {}

    /** Undo snapshot containing either WorldEdit clipboard or changed blocks list. */
    private static class UndoSnapshot {
        private final Clipboard clipboard;  // null for large pastes
        private final List<ChangedBlock> changedBlocks;  // null for small pastes
        private final org.bukkit.World world;
        private final BlockVector3 min;
        private final BlockVector3 max;

        private UndoSnapshot(Clipboard clipboard, BlockVector3 min, BlockVector3 max) {
            this.clipboard = clipboard;
            this.changedBlocks = null;
            this.world = null;
            this.min = min;
            this.max = max;
        }

        private UndoSnapshot(List<ChangedBlock> changedBlocks, org.bukkit.World world, BlockVector3 min, BlockVector3 max) {
            this.clipboard = null;
            this.changedBlocks = new ArrayList<>(changedBlocks);  // defensive copy
            this.world = world;
            this.min = min;
            this.max = max;
        }

        private boolean hasClipboard() { return clipboard != null; }
        private boolean hasChangedBlocks() { return changedBlocks != null && !changedBlocks.isEmpty(); }
    }

    private static class ActivePaste {
        private final org.bukkit.World world;
        private final String displayName;
        private final List<ChangedBlock> changedBlocks = new ArrayList<>();
        private BukkitTask task;
        private boolean cancelled;

        private ActivePaste(org.bukkit.World world, String displayName) {
            this.world = world;
            this.displayName = displayName;
        }
    }

    public PasteCannonCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private File getSchematicsFolder() {
        return new File(plugin.getDataFolder().getParentFile(), "WorldEdit" + File.separator + "schematics");
    }

    // =========================================================================
    // /pastecannon command
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return true;
        }

        if (!player.hasPermission("simplefactionsraiding.pastecannon")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            listSchematics(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            cancelActivePaste(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("undo")) {
            undoLast(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("redo")) {
            redoLast(player);
            return true;
        }

        if (activePastes.containsKey(player.getUniqueId())) {
            player.sendMessage("§cYou already have an active paste. Use §e/pastecannon cancel §cor wait for it to finish.");
            return true;
        }

        // Parse flags
        String name = null;
        boolean ignoreAir = false;
        boolean preserveOrigin = false;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("-a")) {
                ignoreAir = true;
            } else if (arg.equalsIgnoreCase("-o")) {
                preserveOrigin = true;
            } else if (name == null) {
                name = arg;
            }
        }

        if (name == null) {
            listSchematics(player);
            return true;
        }

        File schematicsFolder = getSchematicsFolder();
        File file = resolveSchematic(schematicsFolder, name);

        if (file == null) {
            player.sendMessage("§cSchematic §e" + name + " §cnot found.");
            player.sendMessage("§7Use §e/pastecannon §7to list available schematics.");
            return true;
        }

        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            player.sendMessage("§cUnrecognised schematic format for §e" + file.getName());
            return true;
        }

        player.sendMessage("§7Pasting §e" + file.getName() + "§7...");

        final File finalFile = file;
        final boolean finalIgnoreAir = ignoreAir;
        final boolean finalPreserveOrigin = preserveOrigin;

        // Read the file off the main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (ClipboardReader reader = format.getReader(new FileInputStream(finalFile))) {
                Clipboard clipboard = reader.read();
                // Paste back on the main thread (WorldEdit requires sync access)
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        pasteClipboard(player, clipboard, finalFile.getName(), finalIgnoreAir, finalPreserveOrigin));
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cFailed to read schematic: §7" + e.getMessage()));
                plugin.getLogger().warning("[PasteCannon] Read error: " + e.getMessage());
            }
        });

        return true;
    }

    private void pasteClipboard(Player player, Clipboard clipboard, String displayName, boolean ignoreAir, boolean preserveOrigin) {
        org.bukkit.World bukkitWorld = player.getWorld();
        ActivePaste activePaste = new ActivePaste(bukkitWorld, displayName);
        activePastes.put(player.getUniqueId(), activePaste);

        World weWorld = BukkitAdapter.adapt(bukkitWorld);
        int rotation = preserveOrigin ? 0 : facingToRotation(player.getLocation().getYaw());
        BlockVector3 pasteOrigin = BukkitAdapter.asBlockVector(player.getLocation());
        BlockVector3 clipboardOrigin = clipboard.getOrigin();
        long schematicVolume = clipboard.getRegion().getVolume();

        // Compute a conservative world-space bounding box for the pre-paste snapshot
        BlockVector3 clipOriginPt = clipboard.getOrigin();
        BlockVector3 relMin = clipboard.getMinimumPoint().subtract(clipOriginPt);
        BlockVector3 relMax = clipboard.getMaximumPoint().subtract(clipOriginPt);
        int halfXZ = Math.max(
            Math.max(Math.abs(relMin.x()), Math.abs(relMax.x())),
            Math.max(Math.abs(relMin.z()), Math.abs(relMax.z()))
        );
        int minY = Math.min(relMin.y(), relMax.y());
        int maxY = Math.max(relMin.y(), relMax.y());
        BlockVector3 wMin = pasteOrigin.add(-halfXZ, minY - 1, -halfXZ);
        BlockVector3 wMax = pasteOrigin.add(halfXZ, maxY + 1, halfXZ);

        long snapshotVolume =
            (long) (wMax.x() - wMin.x() + 1)
                * (long) (wMax.y() - wMin.y() + 1)
                * (long) (wMax.z() - wMin.z() + 1);
        boolean materialOnlyMode = schematicVolume >= MATERIAL_ONLY_PASTE_THRESHOLD;

        Clipboard preSnapshot = null;
        if (snapshotVolume <= MAX_UNDO_SNAPSHOT_BLOCKS) {
            preSnapshot = captureRegion(weWorld, wMin, wMax);
        } else {
            player.sendMessage("§eLarge paste region detected (§6" + snapshotVolume + "§e blocks). Undo snapshot disabled for performance.");
        }

        if (materialOnlyMode) {
            player.sendMessage("§eLarge schematic compatibility mode enabled: using base block materials for full render reliability.");
        }

        final Clipboard snapshotRef = preSnapshot;

        player.sendMessage("§7Preloading chunks before paste...");
        preloadChunksThenPaste(
                player,
                bukkitWorld,
                wMin,
                wMax,
                activePaste,
                () -> {
                    player.sendMessage("§7Streaming paste start: up to §e" + schematicVolume + " §7source blocks.");
                    streamPaste(player, bukkitWorld, clipboard, clipboardOrigin, pasteOrigin, rotation, ignoreAir, materialOnlyMode, snapshotRef, displayName, activePaste, wMin, wMax);
                }
        );
    }

    private void preloadChunksThenPaste(Player player,
                                        org.bukkit.World world,
                                        BlockVector3 wMin,
                                        BlockVector3 wMax,
                                        ActivePaste activePaste,
                                        Runnable onComplete) {
        int minChunkX = Math.floorDiv(wMin.x(), 16);
        int maxChunkX = Math.floorDiv(wMax.x(), 16);
        int minChunkZ = Math.floorDiv(wMin.z(), 16);
        int maxChunkZ = Math.floorDiv(wMax.z(), 16);

        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (totalChunks <= 0) {
            onComplete.run();
            return;
        }

        List<ChunkCoord> chunks = new ArrayList<>(totalChunks);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkCoord(chunkX, chunkZ));
            }
        }

        player.sendMessage("§7Chunk preload: §e" + totalChunks + " §7chunks queued.");

        BukkitTask preloadTask = new BukkitRunnable() {
            int index = 0;
            int loaded = 0;
            int ticks = 0;

            @Override
            public void run() {
                if (activePaste.cancelled) {
                    cancel();
                    return;
                }

                ticks++;
                int end = Math.min(index + CHUNKS_PER_TICK, chunks.size());
                for (int i = index; i < end; i++) {
                    ChunkCoord chunk = chunks.get(i);
                    try {
                        if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
                            world.loadChunk(chunk.x(), chunk.z(), true);
                        }
                        loaded++;
                    } catch (Exception ignored) {
                    }
                }
                index = end;

                if (ticks % 40 == 0) {
                    player.sendMessage("§7Chunk preload progress: §e" + loaded + "§7/§e" + totalChunks);
                }

                if (index >= chunks.size()) {
                    cancel();
                    if (!activePaste.cancelled) {
                        player.sendMessage("§aChunk preload complete: §e" + loaded + "§a chunks.");
                        onComplete.run();
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        activePaste.task = preloadTask;
    }

    private void streamPaste(Player player,
                             org.bukkit.World bukkitWorld,
                             Clipboard clipboard,
                             BlockVector3 clipboardOrigin,
                             BlockVector3 pasteOrigin,
                             int rotation,
                             boolean ignoreAir,
                             boolean materialOnlyMode,
                             Clipboard preSnapshot,
                             String displayName,
                             ActivePaste activePaste,
                             BlockVector3 wMin,
                             BlockVector3 wMax) {
        final Iterator<BlockVector3> iterator = clipboard.getRegion().iterator();
        final long estimatedTotal = clipboard.getRegion().getVolume();
        final Clipboard snapshotRef = preSnapshot;

        BukkitTask pasteTask = new BukkitRunnable() {
            long scanned = 0;
            long placed = 0;
            int ticks = 0;

            @Override
            public void run() {
                if (activePaste.cancelled) {
                    cancel();
                    return;
                }

                ticks++;
                int scannedThisTick = 0;
                int placedThisTick = 0;

                while (iterator.hasNext()
                        && scannedThisTick < MAX_SCANNED_PER_TICK
                        && placedThisTick < BLOCKS_PER_TICK) {
                    BlockVector3 pos = iterator.next();
                    scannedThisTick++;
                    scanned++;

                    BlockState state = clipboard.getBlock(pos);
                    if (ignoreAir && isAirState(state)) {
                        continue;
                    }

                    org.bukkit.block.data.BlockData data = toSafeBlockData(state, materialOnlyMode);
                    if (data == null) {
                        continue;
                    }

                    int relX = pos.x() - clipboardOrigin.x();
                    int relY = pos.y() - clipboardOrigin.y();
                    int relZ = pos.z() - clipboardOrigin.z();

                    int[] rotated = rotateXZ(relX, relZ, rotation);
                    int worldX = pasteOrigin.x() + rotated[0];
                    int worldY = pasteOrigin.y() + relY;
                    int worldZ = pasteOrigin.z() + rotated[1];

                    try {
                        org.bukkit.block.Block targetBlock = bukkitWorld.getBlockAt(worldX, worldY, worldZ);
                        org.bukkit.block.data.BlockData oldData = targetBlock.getBlockData();
                        if (oldData.matches(data)) {
                            continue;
                        }
                        activePaste.changedBlocks.add(new ChangedBlock(worldX, worldY, worldZ, oldData));
                        targetBlock.setBlockData(data, false);
                        placedThisTick++;
                        placed++;
                    } catch (Exception ignored) {
                    }
                }

                if (ticks % 40 == 0) {
                    player.sendMessage("§7Paste progress: §e" + placed + "§7 placed / ~§e" + estimatedTotal + "§7 scanned §8(" + scanned + ")");
                }

                if (!iterator.hasNext()) {
                    cancel();
                    activePastes.remove(player.getUniqueId());
                    player.sendMessage("§aPasted §e" + displayName + " §a(" + placed + " blocks placed).");
                    if (rotation != 0) {
                        player.sendMessage("§7Rotated §e" + rotation + "° §7to match your facing direction.");
                    } else if (rotation == 0) {
                        player.sendMessage("§7Original block orientations preserved.");
                    }
                    
                    // Save undo snapshot (clipboard for small pastes, changed blocks for large pastes)
                    UndoSnapshot undoSnapshot;
                    if (snapshotRef != null) {
                        undoSnapshot = new UndoSnapshot(snapshotRef, wMin, wMax);
                        player.sendMessage("§7Use §e/pastecannon undo §7to reverse.");
                    } else if (!activePaste.changedBlocks.isEmpty()) {
                        undoSnapshot = new UndoSnapshot(activePaste.changedBlocks, bukkitWorld, wMin, wMax);
                        player.sendMessage("§7Use §e/pastecannon undo §7to reverse (" + activePaste.changedBlocks.size() + " blocks tracked).");
                    } else {
                        undoSnapshot = null;
                    }
                    
                    if (undoSnapshot != null) {
                        Deque<UndoSnapshot> stack = undoHistory.computeIfAbsent(
                                player.getUniqueId(), k -> new ArrayDeque<>());
                        stack.push(undoSnapshot);
                        if (stack.size() > MAX_HISTORY) {
                            stack.pollLast();
                        }
                        redoHistory.remove(player.getUniqueId());
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        activePaste.task = pasteTask;
    }

    private void cancelActivePaste(Player player) {
        ActivePaste activePaste = activePastes.remove(player.getUniqueId());
        if (activePaste == null) {
            player.sendMessage("§cYou have no active paste to cancel.");
            return;
        }

        activePaste.cancelled = true;
        if (activePaste.task != null) {
            activePaste.task.cancel();
        }

        if (activePaste.changedBlocks.isEmpty()) {
            player.sendMessage("§ePaste cancelled. No blocks were changed.");
            return;
        }

        player.sendMessage("§ePaste cancelled. Reverting §6" + activePaste.changedBlocks.size() + "§e changed blocks...");
        applyRollbackBatched(player, activePaste);
    }

    private void applyRollbackBatched(Player player, ActivePaste activePaste) {
        List<ChangedBlock> changes = activePaste.changedBlocks;

        new BukkitRunnable() {
            int index = changes.size() - 1;

            @Override
            public void run() {
                int applied = 0;
                while (index >= 0 && applied < BLOCKS_PER_TICK) {
                    ChangedBlock changedBlock = changes.get(index);
                    try {
                        activePaste.world.getBlockAt(changedBlock.x(), changedBlock.y(), changedBlock.z())
                                .setBlockData(changedBlock.oldData(), false);
                    } catch (Exception ignored) {
                    }
                    index--;
                    applied++;
                }

                if (index < 0) {
                    cancel();
                    player.sendMessage("§aPaste rollback complete.");
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
    
    /**
     * Apply a list of changed blocks to the world in batches for undo/redo.
     */
    private void applyChangedBlocksBatched(Player player, org.bukkit.World world, List<ChangedBlock> changes, Runnable onComplete) {
        new BukkitRunnable() {
            int index = changes.size() - 1;

            @Override
            public void run() {
                int applied = 0;
                while (index >= 0 && applied < BLOCKS_PER_TICK) {
                    ChangedBlock changedBlock = changes.get(index);
                    try {
                        world.getBlockAt(changedBlock.x(), changedBlock.y(), changedBlock.z())
                                .setBlockData(changedBlock.oldData(), false);
                    } catch (Exception ignored) {
                    }
                    index--;
                    applied++;
                }

                if (index < 0) {
                    cancel();
                    onComplete.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean isAirState(BlockState state) {
        String id = state.getBlockType().id();
        return "minecraft:air".equals(id)
                || "minecraft:cave_air".equals(id)
                || "minecraft:void_air".equals(id);
    }

    private org.bukkit.block.data.BlockData toSafeBlockData(BlockState state, boolean materialOnlyMode) {
        if (materialOnlyMode) {
            Material material = toMaterial(state.getBlockType().id());
            if (material == null || material.isAir()) {
                return null;
            }
            try {
                return material.createBlockData();
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            return BukkitAdapter.adapt(state);
        } catch (Exception ex) {
            Material material = toMaterial(state.getBlockType().id());
            if (material == null || material.isAir()) {
                return null;
            }
            try {
                return material.createBlockData();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Material toMaterial(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        String id = blockId.toUpperCase();
        if (id.startsWith("MINECRAFT:")) {
            id = id.substring("MINECRAFT:".length());
        }
        return Material.matchMaterial(id);
    }

    private int[] rotateXZ(int x, int z, int rotation) {
        return switch (((rotation % 360) + 360) % 360) {
            case 90 -> new int[]{-z, x};
            case 180 -> new int[]{-x, -z};
            case 270 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    // =========================================================================
    // Undo / Redo
    // =========================================================================

    private void undoLast(Player player) {
        Deque<UndoSnapshot> stack = undoHistory.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.sendMessage("§cNothing to undo.");
            return;
        }
        UndoSnapshot snapshot = stack.pop();
        org.bukkit.World bWorld = player.getWorld();
        
        player.sendMessage("§7Applying undo...");
        
        // Capture current state for redo
        UndoSnapshot redoSnapshot;
        if (snapshot.hasClipboard()) {
            World weWorld = BukkitAdapter.adapt(bWorld);
            Clipboard currentState = captureRegion(weWorld, snapshot.min, snapshot.max);
            redoSnapshot = currentState != null ? new UndoSnapshot(currentState, snapshot.min, snapshot.max) : null;
        } else if (snapshot.hasChangedBlocks()) {
            // For changed blocks, capture current state of those blocks before reverting
            List<ChangedBlock> currentBlocks = new ArrayList<>();
            for (ChangedBlock cb : snapshot.changedBlocks) {
                org.bukkit.block.Block block = snapshot.world.getBlockAt(cb.x(), cb.y(), cb.z());
                currentBlocks.add(new ChangedBlock(cb.x(), cb.y(), cb.z(), block.getBlockData()));
            }
            redoSnapshot = new UndoSnapshot(currentBlocks, snapshot.world, snapshot.min, snapshot.max);
        } else {
            redoSnapshot = null;
        }
        
        // Apply the undo
        if (snapshot.hasClipboard()) {
            applySnapshotBatched(player, bWorld, snapshot.clipboard, () -> {
                finishUndo(player, redoSnapshot);
            });
        } else if (snapshot.hasChangedBlocks()) {
            applyChangedBlocksBatched(player, snapshot.world, snapshot.changedBlocks, () -> {
                finishUndo(player, redoSnapshot);
            });
        }
    }
    
    private void finishUndo(Player player, UndoSnapshot redoSnapshot) {
        player.sendMessage("§aUndo complete. §7Use §e/pastecannon redo §7to re-apply.");
        if (redoSnapshot != null) {
            Deque<UndoSnapshot> redoStack = redoHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            redoStack.push(redoSnapshot);
            if (redoStack.size() > MAX_HISTORY) redoStack.pollLast();
        }
    }

    private void redoLast(Player player) {
        Deque<UndoSnapshot> stack = redoHistory.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.sendMessage("§cNothing to redo.");
            return;
        }
        UndoSnapshot snapshot = stack.pop();
        org.bukkit.World bWorld = player.getWorld();
        
        player.sendMessage("§7Applying redo...");
        
        // Capture current state for undo
        UndoSnapshot undoSnapshot;
        if (snapshot.hasClipboard()) {
            World weWorld = BukkitAdapter.adapt(bWorld);
            Clipboard currentState = captureRegion(weWorld, snapshot.min, snapshot.max);
            undoSnapshot = currentState != null ? new UndoSnapshot(currentState, snapshot.min, snapshot.max) : null;
        } else if (snapshot.hasChangedBlocks()) {
            List<ChangedBlock> currentBlocks = new ArrayList<>();
            for (ChangedBlock cb : snapshot.changedBlocks) {
                org.bukkit.block.Block block = snapshot.world.getBlockAt(cb.x(), cb.y(), cb.z());
                currentBlocks.add(new ChangedBlock(cb.x(), cb.y(), cb.z(), block.getBlockData()));
            }
            undoSnapshot = new UndoSnapshot(currentBlocks, snapshot.world, snapshot.min, snapshot.max);
        } else {
            undoSnapshot = null;
        }
        
        // Apply the redo
        if (snapshot.hasClipboard()) {
            applySnapshotBatched(player, bWorld, snapshot.clipboard, () -> {
                finishRedo(player, undoSnapshot);
            });
        } else if (snapshot.hasChangedBlocks()) {
            applyChangedBlocksBatched(player, snapshot.world, snapshot.changedBlocks, () -> {
                finishRedo(player, undoSnapshot);
            });
        }
    }
    
    private void finishRedo(Player player, UndoSnapshot undoSnapshot) {
        player.sendMessage("§aRedo complete.");
        if (undoSnapshot != null) {
            Deque<UndoSnapshot> undoStack = undoHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            undoStack.push(undoSnapshot);
            if (undoStack.size() > MAX_HISTORY) undoStack.pollLast();
        }
    }

    // =========================================================================
    // WorldEdit helpers
    // =========================================================================

    /** Capture a world region into an in-memory Clipboard. Returns null on error. */
    private Clipboard captureRegion(World weWorld, BlockVector3 min, BlockVector3 max) {
        try {
            CuboidRegion region = new CuboidRegion(weWorld, min, max);
            BlockArrayClipboard snapshot = new BlockArrayClipboard(region);
            try (EditSession captureSession = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(weWorld)
                    .build()) {
                captureSession.setFastMode(true);
                captureSession.setSideEffectApplier(SideEffectSet.none());
                ForwardExtentCopy copy = new ForwardExtentCopy(captureSession, region, snapshot, min);
                copy.setCopyingEntities(false);
                copy.setCopyingBiomes(false);
                Operations.complete(copy);
            }
            return snapshot;
        } catch (Exception e) {
            plugin.getLogger().warning("[PasteCannon] Snapshot failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Applies a snapshot clipboard back to the world in batches of BLOCKS_PER_TICK.
     * onComplete is called on the main thread after the last batch finishes.
     */
    private void applySnapshotBatched(Player player, org.bukkit.World bukkitWorld,
                                      Clipboard snapshot, Runnable onComplete) {
        List<PendingBlock> pending = new ArrayList<>();
        for (BlockVector3 pos : snapshot.getRegion()) {
            BlockState state = snapshot.getBlock(pos);
            pending.add(new PendingBlock(pos.x(), pos.y(), pos.z(),
                    BukkitAdapter.adapt(state)));
        }
        if (pending.isEmpty()) {
            player.sendMessage("§cSnapshot was empty, nothing to restore.");
            return;
        }
        new BukkitRunnable() {
            int index = 0;
            @Override
            public void run() {
                int end = Math.min(index + BLOCKS_PER_TICK, pending.size());
                for (int i = index; i < end; i++) {
                    PendingBlock pb = pending.get(i);
                    try {
                        bukkitWorld.getBlockAt(pb.x(), pb.y(), pb.z()).setBlockData(pb.data(), false);
                    } catch (Exception ignored) {}
                }
                index = end;
                if (index >= pending.size()) {
                    cancel();
                    onComplete.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Converts player yaw to a WorldEdit Y-rotation angle (degrees, CW).
     * Snaps to the nearest 90° increment.
     *
     * WorldEdit rotateY is counter-clockwise internally, so we negate.
     *   Yaw   0  = facing South (-Z) → rotation   0
     *   Yaw  90  = facing West  (-X) → rotation  90
     *   Yaw 180  = facing North (+Z) → rotation 180
     *   Yaw 270  = facing East  (+X) → rotation 270
     */
    private static int facingToRotation(float yaw) {
        // Normalise yaw to [0, 360)
        float y = ((yaw % 360) + 360) % 360;
        // Snap to nearest 90
        int snapped = (int) (Math.round(y / 90.0) * 90) % 360;
        // Negate for WorldEdit's CCW convention
        return (360 - snapped) % 360;
    }

    // =========================================================================
    // Schematic listing
    // =========================================================================

    private void listSchematics(Player player) {
        File folder = getSchematicsFolder();

        if (!folder.exists() || !folder.isDirectory()) {
            player.sendMessage("§cNo schematics folder found at §7plugins/WorldEdit/schematics/");
            return;
        }

        File[] files = listSchematicFiles(folder);

        if (files == null || files.length == 0) {
            player.sendMessage("§cNo schematics found in §7plugins/WorldEdit/schematics/");
            return;
        }

        player.sendMessage(Component.text("Available Schematics ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("(click to paste)", NamedTextColor.DARK_GRAY)));

        for (File f : files) {
            String nameNoExt = stripExtension(f.getName());

            Component entry = Component.text(" » ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(f.getName(), NamedTextColor.YELLOW))
                    .clickEvent(ClickEvent.runCommand("/pastecannon " + nameNoExt))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Click to paste ", NamedTextColor.GREEN)
                                    .append(Component.text(f.getName(), NamedTextColor.YELLOW))
                                    .append(Component.newline())
                                    .append(Component.text("Add ", NamedTextColor.GRAY))
                                    .append(Component.text("-a ", NamedTextColor.YELLOW))
                                    .append(Component.text("to ignore air, ", NamedTextColor.GRAY))
                                    .append(Component.text("-o ", NamedTextColor.YELLOW))
                                    .append(Component.text("to preserve orientations", NamedTextColor.GRAY))
                    ));
            player.sendMessage(entry);
        }

        player.sendMessage("§7Usage: §e/pastecannon <name> §8[§e-a §8= ignore air] [§e-o §8= preserve orientations]");
        player.sendMessage("§7       §e/pastecannon undo §8| §e/pastecannon redo §8| §e/pastecannon cancel");
    }

    private File[] listSchematicFiles(File folder) {
        return folder.listFiles(f ->
                f.isFile() && (f.getName().endsWith(".schem") || f.getName().endsWith(".schematic")));
    }

    /**
     * Resolves a schematic by name, trying exact name first then appending extensions.
     * Returns null if not found.
     */
    private File resolveSchematic(File folder, String name) {
        // Try exact name
        File f = new File(folder, name);
        if (f.exists() && f.isFile()) return f;
        // Try .schem
        f = new File(folder, name + ".schem");
        if (f.exists() && f.isFile()) return f;
        // Try .schematic
        f = new File(folder, name + ".schematic");
        if (f.exists() && f.isFile()) return f;
        return null;
    }

    private static String stripExtension(String name) {
        if (name.endsWith(".schem"))     return name.substring(0, name.length() - 6);
        if (name.endsWith(".schematic")) return name.substring(0, name.length() - 10);
        return name;
    }

    // =========================================================================
    // Tab completion
    // =========================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("undo", "redo", "cancel"));
            File folder = getSchematicsFolder();
            if (folder.exists()) {
                File[] files = listSchematicFiles(folder);
                if (files != null) {
                    for (File f : files) {
                        options.add(stripExtension(f.getName()));
                    }
                }
            }
            String partial = args[0].toLowerCase();
            options.removeIf(s -> !s.toLowerCase().startsWith(partial));
            return options;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("undo")
                && !args[0].equalsIgnoreCase("redo")
                && !args[0].equalsIgnoreCase("cancel")) {
            List<String> flags = new ArrayList<>();
            if (!Arrays.asList(args).contains("-a")) flags.add("-a");
            if (!Arrays.asList(args).contains("-o")) flags.add("-o");
            return flags;
        }
        if (args.length == 3 && !args[0].equalsIgnoreCase("undo")
                && !args[0].equalsIgnoreCase("redo")
                && !args[0].equalsIgnoreCase("cancel")) {
            List<String> flags = new ArrayList<>();
            if (!Arrays.asList(args).contains("-a")) flags.add("-a");
            if (!Arrays.asList(args).contains("-o")) flags.add("-o");
            return flags;
        }
        return List.of();
    }
}
