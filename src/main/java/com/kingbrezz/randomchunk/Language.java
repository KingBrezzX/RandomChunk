package com.kingbrezz.randomchunk;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

public final class Language {

    private final RandomChunkPlugin plugin;
    private FileConfiguration messages;

    public Language(RandomChunkPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");

        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String path) {
        String prefix = messages.getString("prefix", "");
        String message = messages.getString(path, path);

        return color(prefix + message);
    }

    public String get(String path, Map<String, String> placeholders) {
        String message = get(path);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(
                    "{" + entry.getKey() + "}",
                    entry.getValue()
            );
        }

        return message;
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
          }
