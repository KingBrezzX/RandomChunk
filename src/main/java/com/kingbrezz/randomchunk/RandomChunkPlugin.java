package com.kingbrezz.randomchunk;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class RandomChunkPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Random random = new Random();

    private final Queue<ChunkTask> queue = new ArrayDeque<>();
    private final Set<String> queuedChunks = new HashSet<>();
    private final Map<UUID, String> lastChunkByPlayer = new HashMap<>();

    private final List<Material> randomMaterials = new ArrayList<>();

    private ProcessedChunks processedChunks;
    private Language language;

    private BukkitTask processingTask;

    private boolean running;
    private int activeChunks;

    private boolean replaceAir;
    private boolean replaceBedrock;
    private boolean applyPhysics;

    private int blocksPerTick;
    private int slowModeBlocksPerTick;
    private int maxActiveChunks;

    private boolean adaptiveTps;
    private double minimumTps;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        language = new Language(this);
        processedChunks = new ProcessedChunks(this);

        loadSettings();
        rebuildMaterialList();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("randomchunk") != null) {
            getCommand("randomchunk").setExecutor(this);
            getCommand("randomchunk").setTabCompleter(this);
        }

        running = getConfig().getBoolean("settings.enabled-on-start", false);

        startProcessingTask();

        getLogger().info("RandomChunk enabled.");
        getLogger().info("Random block pool: " + randomMaterials.size());

        if (running) {
            getLogger().info("RandomChunk event is running.");
        }
    }

    @Override
    public void onDisable() {
        if (processingTask != null) {
            processingTask.cancel();
            processingTask = null;
        }

        if (processedChunks != null) {
            processedChunks.save();
        }

        queue.clear();
        queuedChunks.clear();
        lastChunkByPlayer.clear();

        getLogger().info("RandomChunk disabled.");
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != this) {
            return;
        }

        if (processingTask != null) {
            processingTask.cancel();
        }
    }

    private void loadSettings() {
        replaceAir = getConfig().getBoolean(
                "settings.replace-air",
                false
        );

        replaceBedrock = getConfig().getBoolean(
                "settings.replace-bedrock",
                true
        );

        applyPhysics = getConfig().getBoolean(
                "settings.apply-physics",
                false
        );

        blocksPerTick = Math.max(
                1,
                getConfig().getInt(
                        "settings.blocks-per-tick",
                        2048
                )
        );

        slowModeBlocksPerTick = Math.max(
                1,
                getConfig().getInt(
                        "performance.slow-mode-blocks-per-tick",
                        256
                )
        );

        maxActiveChunks = Math.max(
                1,
                getConfig().getInt(
                        "settings.max-active-chunks",
                        1
                )
        );

        adaptiveTps = getConfig().getBoolean(
                "performance.adaptive-tps",
                true
        );

        minimumTps = getConfig().getDouble(
                "performance.minimum-tps",
                18.0
        );
    }

    private void rebuildMaterialList() {
        randomMaterials.clear();

        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }

            if (material.isAir()) {
                continue;
            }

            randomMaterials.add(material);
        }

        Collections.shuffle(randomMaterials, random);
    }

    private void startProcessingTask() {
        if (processingTask != null) {
            processingTask.cancel();
        }

        processingTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::processQueue,
                1L,
                1L
        );
    }

    private void processQueue() {
        if (!running) {
            return;
        }

        if (queue.isEmpty()) {
            return;
        }

        int availableSlots = maxActiveChunks - activeChunks;

        if (availableSlots <= 0) {
            return;
        }

        while (availableSlots > 0 && !queue.isEmpty()) {
            ChunkTask task = queue.peek();

            if (task == null) {
                return;
            }

            if (!task.world.isChunkLoaded(task.chunkX, task.chunkZ)) {
                return;
            }

            activeChunks++;

            int budget = getCurrentBlocksPerTick();

            boolean complete = processBlocks(task, budget);

            if (complete) {
                queue.poll();

                queuedChunks.remove(task.key);

                activeChunks = Math.max(
                        0,
                        activeChunks - 1
                );

                Chunk chunk = task.world.getChunkAt(
                        task.chunkX,
                        task.chunkZ
                );

                processedChunks.add(chunk);
                processedChunks.save();

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put(
                        "chunk_x",
                        Integer.toString(task.chunkX)
                );
                placeholders.put(
                        "chunk_z",
                        Integer.toString(task.chunkZ)
                );
                placeholders.put(
                        "block",
                        task.material.name()
                );

                getLogger().info(
                        "Chunk " +
                                task.chunkX +
                                "," +
                                task.chunkZ +
                                " in " +
                                task.world.getName() +
                                " became " +
                                task.material.name()
                );
            } else {
                activeChunks = Math.max(
                        0,
                        activeChunks - 1
                );

                return;
            }

            availableSlots--;
        }
    }

    private int getCurrentBlocksPerTick() {
        if (!adaptiveTps) {
            return blocksPerTick;
        }

        double[] tps = Bukkit.getTPS();

        if (tps.length == 0) {
            return blocksPerTick;
        }

        double currentTps = tps[0];

        if (currentTps < minimumTps) {
            return slowModeBlocksPerTick;
        }

        return blocksPerTick;
    }

    private boolean processBlocks(
            ChunkTask task,
            int budget
    ) {
        int processed = 0;

        int minY = task.world.getMinHeight();
        int maxY = task.world.getMaxHeight();

        while (processed < budget) {

            if (task.y >= maxY) {
                return true;
            }

            int worldX = task.chunkX * 16 + task.localX;
            int worldZ = task.chunkZ * 16 + task.localZ;

            Block block = task.world.getBlockAt(
                    worldX,
                    task.y,
                    worldZ
            );

            if (shouldReplace(block)) {
                block.setType(
                        task.material,
                        applyPhysics
                );
            }

            processed++;

            task.localX++;

            if (task.localX >= 16) {
                task.localX = 0;
                task.localZ++;

                if (task.localZ >= 16) {
                    task.localZ = 0;
                    task.y++;
                }
            }
        }

        return false;
    }

    private boolean shouldReplace(Block block) {
        Material type = block.getType();

        if (type.isAir()) {
            return replaceAir;
        }

        if (type == Material.BEDROCK && !replaceBedrock) {
            return false;
        }

        return true;
    }

    private boolean isWorldEnabled(World world) {
        List<String> enabledWorlds =
                getConfig().getStringList("worlds.enabled");

        if (enabledWorlds.isEmpty()) {
            return true;
        }

        return enabledWorlds.contains(world.getName());
    }

    private void handleChunkChange(
            Player player,
            int chunkX,
            int chunkZ
    ) {
        if (!running) {
            return;
        }

        World world = player.getWorld();

        if (!isWorldEnabled(world)) {
            return;
        }

        String playerKey =
                world.getUID() +
                        ":" +
                        chunkX +
                        ":" +
                        chunkZ;

        String previous =
                lastChunkByPlayer.put(
                        player.getUniqueId(),
                        playerKey
                );

        if (playerKey.equals(previous)) {
            return;
        }

        Chunk chunk = world.getChunkAt(
                chunkX,
                chunkZ
        );

        if (processedChunks.contains(chunk)) {
            return;
        }

        enqueueChunk(world, chunkX, chunkZ);
    }

    private void enqueueChunk(
            World world,
            int chunkX,
            int chunkZ
    ) {
        String key =
                world.getUID() +
                        ":" +
                        chunkX +
                        ":" +
                        chunkZ;

        if (queuedChunks.contains(key)) {
            return;
        }

        Chunk chunk = world.getChunkAt(
                chunkX,
                chunkZ
        );

        if (processedChunks.contains(chunk)) {
            return;
        }

        if (randomMaterials.isEmpty()) {
            rebuildMaterialList();
        }

        if (randomMaterials.isEmpty()) {
            getLogger().severe(
                    "No valid Minecraft blocks are available."
            );
            return;
        }

        Material material =
                randomMaterials.get(
                        random.nextInt(
                                randomMaterials.size()
                        )
                );

        ChunkTask task = new ChunkTask(
                world,
                chunkX,
                chunkZ,
                material,
                world.getMinHeight()
        );

        queue.add(task);
        queuedChunks.add(key);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running) {
            return;
        }

        if (event.getTo() == null) {
            return;
        }

        if (event.getFrom().getWorld() == null) {
            return;
        }

        if (event.getTo().getWorld() == null) {
            return;
        }

        if (
                event.getFrom().getBlockX() ==
                        event.getTo().getBlockX()
                        &&
                        event.getFrom().getBlockZ() ==
                                event.getTo().getBlockZ()
        ) {
            return;
        }

        int oldChunkX =
                event.getFrom().getBlockX() >> 4;

        int oldChunkZ =
                event.getFrom().getBlockZ() >> 4;

        int newChunkX =
                event.getTo().getBlockX() >> 4;

        int newChunkZ =
                event.getTo().getBlockZ() >> 4;

        if (
                oldChunkX == newChunkX
                        &&
                        oldChunkZ == newChunkZ
        ) {
            return;
        }

        handleChunkChange(
                event.getPlayer(),
                newChunkX,
                newChunkZ
        );
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!running) {
            return;
        }

        Player player = event.getPlayer();

        lastChunkByPlayer.remove(
                player.getUniqueId()
        );

        int chunkX =
                player.getLocation()
                        .getBlockX() >> 4;

        int chunkZ =
                player.getLocation()
                        .getBlockZ() >> 4;

        handleChunkChange(
                player,
                chunkX,
                chunkZ
        );
    }

    private void startEvent(CommandSender sender) {
        if (running) {
            sender.sendMessage(
                    language.get(
                            "event.already-started"
                    )
            );
            return;
        }

        running = true;

        getConfig().set(
                "settings.enabled-on-start",
                true
        );

        saveConfig();

        sender.sendMessage(
                language.get(
                        "event.started"
                )
        );
    }

    private void stopEvent(CommandSender sender) {
        if (!running) {
            sender.sendMessage(
                    language.get(
                            "event.already-stopped"
                    )
            );
            return;
        }

        running = false;

        getConfig().set(
                "settings.enabled-on-start",
                false
        );

        saveConfig();

        sender.sendMessage(
                language.get(
                        "event.stopped"
                )
        );
    }

    private void resetEvent(CommandSender sender) {
        queue.clear();
        queuedChunks.clear();
        lastChunkByPlayer.clear();

        activeChunks = 0;

        sender.sendMessage(
                language.get(
                        "reset.started"
                )
        );

        processedChunks.reset();

        sender.sendMessage(
                language.get(
                        "reset.completed"
                )
        );
    }

    private void reloadPlugin(CommandSender sender) {
        reloadConfig();

        loadSettings();

        rebuildMaterialList();

        language.reload();

        sender.sendMessage(
                language.get(
                        "general.reloaded"
                )
        );
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(
                language.get(
                        "status.header"
                )
        );

        sender.sendMessage(
                language.get(
                        "status.running"
                ).replace(
                        "{running}",
                        Boolean.toString(running)
                )
        );

        sender.sendMessage(
                language.get(
                        "status.processed"
                ).replace(
                        "{processed}",
                        Integer.toString(
                                processedChunks.size()
                        )
                )
        );

        sender.sendMessage(
                language.get(
                        "status.queued"
                ).replace(
                        "{queued}",
                        Integer.toString(
                                queue.size()
                        )
                )
        );

        sender.sendMessage(
                language.get(
                        "status.active"
                ).replace(
                        "{active}",
                        Integer.toString(
                                activeChunks
                        )
                )
        );

        sender.sendMessage(
                language.get(
                        "status.header-end"
                )
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (
                !sender.hasPermission(
                        "randomchunk.admin"
                )
        ) {
            sender.sendMessage(
                    language.get(
                            "general.no-permission"
                    )
            );
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        String subCommand =
                args[0].toLowerCase();

        switch (subCommand) {

            case "start":
                startEvent(sender);
                break;

            case "stop":
                stopEvent(sender);
                break;

            case "reset":
                resetEvent(sender);
                break;

            case "reload":
                reloadPlugin(sender);
                break;

            case "status":
                sendStatus(sender);
                break;

            default:
                sender.sendMessage(
                        language.get(
                                "general.unknown-command"
                        )
                );
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> options =
                    List.of(
                            "start",
                            "stop",
                            "reset",
                            "reload",
                            "status"
                    );

            String input =
                    args[0].toLowerCase();

            List<String> result =
                    new ArrayList<>();

            for (String option : options) {
                if (option.startsWith(input)) {
                    result.add(option);
                }
            }

            return result;
        }

        return Collections.emptyList();
    }

    private static final class ChunkTask {

        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final Material material;
        private final String key;

        private int localX;
        private int localZ;
        private int y;

        private ChunkTask(
                World world,
                int chunkX,
                int chunkZ,
                Material material,
                int minY
        ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.material = material;
            this.y = minY;

            this.key =
                    world.getUID() +
                            ":" +
                            chunkX +
                            ":" +
                            chunkZ;
        }
    }
            }
