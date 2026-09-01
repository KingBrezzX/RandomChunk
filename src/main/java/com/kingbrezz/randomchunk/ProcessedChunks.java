package com.kingbrezz.randomchunk;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class ProcessedChunks {

    private final RandomChunkPlugin plugin;
    private final Set<String> processed =
            new HashSet<>();

    private File file;
    private FileConfiguration data;

    public ProcessedChunks(
            RandomChunkPlugin plugin
    ) {
        this.plugin = plugin;
        load();
    }

    private String key(
            World world,
            int x,
            int z
    ) {
        return world.getUID()
                + ":"
                + x
                + ":"
                + z;
    }

    public boolean contains(Chunk chunk) {
        return containsKey(
                key(
                        chunk.getWorld(),
                        chunk.getX(),
                        chunk.getZ()
                )
        );
    }

    public boolean containsKey(String key) {
        return processed.contains(key);
    }

    public void add(Chunk chunk) {
        processed.add(
                key(
                        chunk.getWorld(),
                        chunk.getX(),
                        chunk.getZ()
                )
        );
    }

    public int size() {
        return processed.size();
    }

    public void reset() {
        processed.clear();
        save();
    }

    public void load() {
        file = new File(
                plugin.getDataFolder(),
                "processed-chunks.yml"
        );

        if (!file.exists()) {
            try {
                File parent =
                        file.getParentFile();

                if (parent != null) {
                    parent.mkdirs();
                }

                file.createNewFile();

            } catch (IOException e) {
                plugin.getLogger().severe(
                        "Could not create processed-chunks.yml: "
                                + e.getMessage()
                );
            }
        }

        data =
                YamlConfiguration.loadConfiguration(
                        file
                );

        processed.clear();

        processed.addAll(
                data.getStringList("chunks")
        );
    }

    public void save() {
        if (file == null) {
            return;
        }

        if (data == null) {
            data =
                    YamlConfiguration.loadConfiguration(
                            file
                    );
        }

        data.set(
                "chunks",
                processed
        );

        try {
            data.save(file);

        } catch (IOException e) {
            plugin.getLogger().severe(
                    "Could not save processed-chunks.yml: "
                            + e.getMessage()
            );
        }
    }
}
