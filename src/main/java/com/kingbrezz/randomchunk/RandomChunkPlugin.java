package com.kingbrezz.randomchunk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

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

public final class RandomChunkPlugin extends JavaPlugin implements Listener {

    private Language language;
    private ProcessedChunks processedChunks;

    private final Queue<ChunkTask> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> active = new HashSet<>();

    private final Map<String, Long> lastChunkByPlayer = new HashMap<>();

    private final Random random = new Random();

    private List<Material> randomMaterials = new ArrayList<>();

    private boolean running;

    private int blocksPerTick;
    private int slowBlocksPerTick;
    private boolean adaptiveTps;
    private double minimumTps;
    private boolean replaceAir;
    private boolean replaceBedrock;
    private boolean applyPhysics;
    private boolean persistent;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        language = new Language(this);
        processedChunks = new ProcessedChunks(this);

        loadSettings();
        rebuildMaterialList();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("RandomChunk enabled.");
        getLogger().info("Author: KingBrezz");
        getLogger().info("Available random blocks: " + randomMaterials.size());

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::processQueue,
                1L,
                1L
        );
    }

    @Override
    public void onDisable() {
        if (persistent && processedChunks != null) {
            processedChunks.save();
        }

        queue.clear();
        queued.clear();
        active.clear();

        getLogger().info("RandomChunk disabled.");
    }

    private void loadSettings() {
        blocksPerTick = Math.max(
                1,
                getConfig().getInt(
                        "settings.blocks-per-tick",
                        2048
                )
        );

        slowBlocksPerTick = Math.max(
                1,
                getConfig().getInt(
                        "performance.slow-mode-blocks-per-tick",
                        256
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

        persistent = getConfig().getBoolean(
                "storage.persistent",
                true
        );

        running = getConfig().getBoolean(
                "settings.enabled-on-start",
                false
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

        Collections.shuffle(randomMaterials);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!running) {
            return;
        }

        if (event.getTo() == null) {
            return;
        }

        int oldChunkX = event.getFrom().getBlockX() >> 4;
        int oldChunkZ = event.getFrom().getBlockZ() >> 4;

        int newChunkX = event.getTo().getBlockX() >> 4;
        int newChunkZ = event.getTo().getBlockZ() >> 4;

        if (oldChunkX == newChunkX && oldChunkZ == newChunkZ) {
            return;
        }

        enqueue(
                event.getPlayer().getWorld(),
                newChunkX,
                newChunkZ
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!running) {
            return;
        }

        Player player = event.getPlayer();

        enqueue(
                player.getWorld(),
                player.getChunk().getX(),
                player.getChunk().getZ()
        );
    }

    private void enqueue(World world, int chunkX, int chunkZ) {
        if (!isWorldEnabled(world)) {
            return;
        }

        if (randomMaterials.isEmpty()) {
            return;
        }

        String key = world.getUID() + ":" + chunkX + ":" + chunkZ;

        if (processedChunks.contains(
                world.getChunkAt(chunkX, chunkZ)
        )) {
            return;
        }

        if (queued.contains(key) || active.contains(key)) {
            return;
        }

        queued.add(key);

        queue.add(
                new ChunkTask(
                        world,
                        chunkX,
                        chunkZ,
                        key,
                        chooseMaterial()
                )
        );
    }

    private Material chooseMaterial() {
        return randomMaterials.get(
                random.nextInt(randomMaterials.size())
        );
    }

    private void processQueue() {
        if (!running || queue.isEmpty()) {
            return;
        }

        if (randomMaterials.isEmpty()) {
            return;
        }

        ChunkTask task = queue.peek();

        if (!task.world.isChunkLoaded(
                task.chunkX,
                task.chunkZ
        )) {
            queue.poll();
            queued.remove(task.key);
            return;
        }

        active.add(task.key);

        int budget = blocksPerTick;

        if (adaptiveTps) {
            double tps = getServerTps();

            if (tps < minimumTps) {
                budget = slowBlocksPerTick;
            }
        }

        boolean finished = processBlocks(
                task,
                budget
        );

        if (!finished) {
            return;
        }

        queue.poll();
        queued.remove(task.key);
        active.remove(task.key);

        Chunk chunk = task.world.getChunkAt(
                task.chunkX,
                task.chunkZ
        );

        processedChunks.add(chunk);

        if (persistent) {
            processedChunks.save();
        }

        getLogger().info(
                "Processed chunk "
                        + task.world.getName()
                        + " "
                        + task.chunkX
                        + ","
                        + task.chunkZ
                        + " -> "
                        + task.material.name()
        );
    }

    private boolean processBlocks(
            ChunkTask task,
            int budget
    ) {
        World world = task.world;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int processed = 0;

        while (
                task.y < maxY
                        && processed < budget
        ) {
            while (
                    task.y < maxY
                            && processed < budget
            ) {
                Block block = world.getBlockAt(
                        task.x,
                        task.y,
                        task.z
                );

                if (shouldReplace(block)) {
                    block.setType(
                            task.material,
                            applyPhysics
                    );
                }

                processed++;

                advance(task);
            }
        }

        return task.y >= maxY;
    }

    private boolean shouldReplace(Block block) {
        Material type = block.getType();

        if (type.isAir()) {
            return replaceAir;
        }

        if (
                type == Material.BEDROCK
                        && !replaceBedrock
        ) {
            return false;
        }

        return true;
    }

    private void advance(ChunkTask task) {
        task.x++;

        if (task.x >= task.maxX) {
            task.x = task.minX;
            task.z++;

            if (task.z >= task.maxZ) {
                task.z = task.minZ;
                task.y++;
            }
        }
    }

    private boolean isWorldEnabled(World world) {
        List<String> enabled = getConfig().getStringList(
                "worlds.enabled"
        );

        if (enabled.isEmpty()) {
            return true;
        }

        return enabled.contains(world.getName());
    }

    private double getServerTps() {
        try {
            double[] tps = Bukkit.getTPS();

            if (tps.length == 0) {
                return 20.0;
            }

            return tps[0];
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    public void startEvent() {
        running = true;
        getConfig().set("settings.enabled-on-start", true);
        saveConfig();
    }

    public void stopEvent() {
        running = false;
        getConfig().set("settings.enabled-on-start", false);
        saveConfig();

        queue.clear();
        queued.clear();
        active.clear();
    }

    public void resetEvent() {
        queue.clear();
        queued.clear();
        active.clear();

        processedChunks.reset();
    }

    public void reloadPlugin() {
        reloadConfig();
        language.reload();
        loadSettings();
        rebuildMaterialList();
    }

    public Language getLanguage() {
        return language;
    }

    public boolean isRunning() {
        return running;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getActiveSize() {
        return active.size();
    }

    public int getProcessedSize() {
        return processedChunks.size();
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("randomchunk.admin")) {
            sender.sendMessage(
                    language.get("general.no-permission")
            );
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(
                    language.get("general.unknown-command")
            );
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "start" -> {
                if (running) {
                    sender.sendMessage(
                            language.get(
                                    "event.already-started"
                            )
                    );
                    return true;
                }

                startEvent();

                sender.sendMessage(
                        language.get(
                                "event.started"
                        )
                );
            }

            case "stop" -> {
                if (!running) {
                    sender.sendMessage(
                            language.get(
                                    "event.already-stopped"
                            )
                    );
                    return true;
                }

                stopEvent();

                sender.sendMessage(
                        language.get(
                                "event.stopped"
                        )
                );
            }

            case "reset" -> {
                resetEvent();

                sender.sendMessage(
                        language.get(
                                "reset.completed"
                        )
                );
            }

            case "reload" -> {
                reloadPlugin();

                sender.sendMessage(
                        language.get(
                                "general.reloaded"
                        )
                );
            }

            case "status" -> {
                sender.sendMessage(
                        language.color(
                                "&8&m----------&r &bRandomChunk &8&m----------"
                        )
                );

                sender.sendMessage(
                        language.get(
                                "status.running"
                        ).replace(
                                "{running}",
                                running ? "true" : "false"
                        )
                );

                sender.sendMessage(
                        language.get(
                                "status.processed"
                        ).replace(
                                "{processed}",
                                String.valueOf(
                                        getProcessedSize()
                                )
                        )
                );

                sender.sendMessage(
                        language.get(
                                "status.queued"
                        ).replace(
                                "{queued}",
                                String.valueOf(
                                        getQueueSize()
                                )
                        )
                );

                sender.sendMessage(
                        language.get(
                                "status.active"
                        ).replace(
                                "{active}",
                                String.valueOf(
                                        getActiveSize()
                                )
                        )
                );

                sender.sendMessage(
                        language.get(
                                "status.header-end"
                        )
                );
            }

            default -> sender.sendMessage(
                    language.get(
                            "general.unknown-command"
                    )
            );
        }

        return true;
    }

    private static final class ChunkTask {

        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final String key;
        private final Material material;

        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;

        private int x;
        private int y;
        private int z;

        private ChunkTask(
                World world,
                int chunkX,
                int chunkZ,
                String key,
                Material material
        ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.key = key;
            this.material = material;

            this.minX = chunkX << 4;
            this.maxX = minX + 16;

            this.minZ = chunkZ << 4;
            this.maxZ = minZ + 16;

            this.x = minX;
            this.y = world.getMinHeight();
            this.z = minZ;
        }
    }
          }
