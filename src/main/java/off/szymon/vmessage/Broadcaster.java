/*
 * vMessage
 * Copyright (c) 2025.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * See the LICENSE file in the project root for details.
 */

package off.szymon.vmessage;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.william278.papiproxybridge.api.PlaceholderAPI;
import off.szymon.vmessage.compatibility.LuckPermsCompatibilityProvider;
import off.szymon.vmessage.config.ConfigManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class Broadcaster {

    private final HashMap<String, String> serverAliases; // Server name, Server alias
    private final LuckPermsCompatibilityProvider lp;
    private final HashMap<String, String> metaPlaceholders; // Placeholder, Meta key

    public Broadcaster() {
        serverAliases = new HashMap<>();
        reloadAliases();

        /* LuckPerms */
        lp = VMessagePlugin.get().getLuckPermsCompatibilityProvider();

        metaPlaceholders = new HashMap<>();
        reloadMetaPlaceholders();
    }

    /**
     * 核心解析逻辑：先处理本地变量，再异步处理 PAPI 变量
     */
    private void finalizeAndBroadcast(String rawMsg, @Nullable Player player) {
        if (player == null || !player.getCurrentServer().isPresent()) {
            VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(rawMsg));
            return;
        }
        String serverName = player.getCurrentServer().get().getServerInfo().getName();
        java.util.List<String> excludedServers = ConfigManager.get().getConfig().getPapiExcludedServers();

        if (excludedServers != null && excludedServers.contains(serverName)) {
            VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(rawMsg));
            return;
        }
        // --------------------------------------

        try {
           PlaceholderAPI api =
                   PlaceholderAPI.createInstance();

            api.formatPlaceholders(rawMsg, player.getUniqueId())
                    .thenAccept(formatted -> {
                        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(formatted));
                    })
                    .exceptionally(ex -> {
                        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(rawMsg));
                        return null;
                    });

        } catch (Throwable t) {
            VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(rawMsg));
        }
    }
    private String applyCommonPlaceholders(String format, Player player) {
        String msg = format
                .replace("%player%", player.getUsername())
                .replace("%server%", parseAlias(player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("Unknown")));

        if (lp != null) {
            LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
            msg = msg
                    .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                    .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

            for (Map.Entry<String, String> entry : metaPlaceholders.entrySet()) {
                msg = msg.replace(entry.getKey(), Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse(""));
            }
        }
        return msg;
    }

    public void message(Player player, String message) {
        if (!ConfigManager.get().getConfig().getMessages().getChat().getEnabled()) return;

        String serverName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");

        java.util.List<String> excludedServers = ConfigManager.get().getConfig().getPapiExcludedServers();
        boolean isExcluded = excludedServers != null && excludedServers.contains(serverName);

        String format;
        if (isExcluded) {
            format = ConfigManager.get().getConfig().getMessages().getChat().getFormatNoPapi();
        } else {
            format = ConfigManager.get().getConfig().getMessages().getChat().getFormat();
        }
        String msg = applyCommonPlaceholders(format, player)
                .replace("%message%", escapeMiniMessage(message));
        finalizeAndBroadcast(msg, player);
    }

    public void join(Player player) {
        if (!ConfigManager.get().getConfig().getMessages().getJoin().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.join")) return;

        String format = ConfigManager.get().getConfig().getMessages().getJoin().getFormat();
        finalizeAndBroadcast(applyCommonPlaceholders(format, player), player);
    }

    public void leave(Player player) {
        if (!ConfigManager.get().getConfig().getMessages().getLeave().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.leave")) return;

        String format = ConfigManager.get().getConfig().getMessages().getLeave().getFormat();
        String serverName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .map(this::parseAlias)
                .orElse(null);

        if (serverName == null) return;

        String msg = applyCommonPlaceholders(format, player);
        finalizeAndBroadcast(msg, player);
    }

    public void change(Player player, String oldServer) {
        if (!ConfigManager.get().getConfig().getMessages().getChange().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.change")) return;

        String format = ConfigManager.get().getConfig().getMessages().getChange().getFormat();
        String msg = applyCommonPlaceholders(format, player)
                .replace("%old_server%", parseAlias(oldServer))
                .replace("%new_server%", parseAlias(player.getCurrentServer().get().getServerInfo().getName()));

        finalizeAndBroadcast(msg, player);
    }

    public void broadcast(String message, @Nullable Player player) {
        String format = ConfigManager.get().getConfig().getCommands().getBroadcast().getFormat();
        String msg;

        if (player != null) {
            String content = ConfigManager.get().getConfig().getCommands().getBroadcast().getAllowMiniMessage()
                    ? message : MiniMessage.miniMessage().escapeTags(message);
            msg = applyCommonPlaceholders(format, player).replace("%message%", content);
            finalizeAndBroadcast(msg, player);
        } else {
            msg = format
                    .replace("%message%", message)
                    .replace("%player%", "Server")
                    .replace("%server%", "Server")
                    .replace("%suffix%", "")
                    .replace("%prefix%", "");
            for (String key : metaPlaceholders.keySet()) msg = msg.replace(key, "");
            finalizeAndBroadcast(msg, null);
        }
    }

    public void reload() {
        reloadAliases();
        reloadMetaPlaceholders();
    }

    public void reloadAliases() {
        serverAliases.clear();
        Set<Map.Entry<Object, CommentedConfigurationNode>> aliases = ConfigManager.get().getNode("server-aliases").childrenMap().entrySet();
        for (Map.Entry<Object, CommentedConfigurationNode> entry : aliases) {
            serverAliases.put(entry.getKey().toString(), entry.getValue().getString(""));
        }
    }

    public void reloadMetaPlaceholders() {
        metaPlaceholders.clear();
        if (lp != null) {
            Set<Map.Entry<Object, CommentedConfigurationNode>> metas = ConfigManager.get().getNode("luck-perms-meta").childrenMap().entrySet();
            for (Map.Entry<Object, CommentedConfigurationNode> entry : metas) {
                metaPlaceholders.put("&" + entry.getKey().toString() + "&", entry.getValue().getString(""));
            }
        }
    }

    public String parseAlias(String serverName) {
        for (Map.Entry<String, String> entry : serverAliases.entrySet()) {
            if (serverName.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return serverName;
    }

    public HashMap<String, String> getMetaPlaceholders() {
        return metaPlaceholders;
    }

    private String escapeMiniMessage(String input) {
        return ConfigManager.get().getConfig().getMessages().getChat().getAllowMiniMessage() ? input : MiniMessage.miniMessage().escapeTags(input);
    }
}