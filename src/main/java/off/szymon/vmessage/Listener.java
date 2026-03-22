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

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.william278.papiproxybridge.api.PlaceholderAPI;
import off.szymon.vmessage.compatibility.LuckPermsCompatibilityProvider;
import off.szymon.vmessage.compatibility.mute.MutePluginCompatibilityProvider;
import off.szymon.vmessage.config.ConfigManager;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class Listener {


    @Subscribe
    private void onMessageSend(PlayerChatEvent e) {
        e.setResult(PlayerChatEvent.ChatResult.denied());

        Player player = e.getPlayer();
        MutePluginCompatibilityProvider mpcp = VMessagePlugin.get().getMutePluginCompatibilityProvider();

        mpcp.isMuted(player).thenAcceptAsync(isMuted -> {
            if (isMuted) {
                handleMutedPlayer(player, e.getMessage());
            } else {
                VMessagePlugin.get().getBroadcaster().message(player, e.getMessage());
            }
        });
    }

    private void handleMutedPlayer(Player player, String originalMessage) {
        VMessagePlugin.get().getMutePluginCompatibilityProvider().getMute(player).thenAcceptAsync(mute -> {
            Broadcaster broadcaster = VMessagePlugin.get().getBroadcaster();

            String rawServerName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
            java.util.List<String> excludedServers = ConfigManager.get().getConfig().getPapiExcludedServers();
            boolean isExcluded = excludedServers != null && excludedServers.contains(rawServerName);

            String msgFormat = isExcluded ?
                    ConfigManager.get().getConfig().getMessages().getChat().getMutedMessageNoPapi() :
                    ConfigManager.get().getConfig().getMessages().getChat().getMutedMessage();

            String serverAlias = player.getCurrentServer()
                    .map(server -> broadcaster.parseAlias(server.getServerInfo().getName()))
                    .orElse("Unknown");

            String processed = msgFormat
                    .replace("%player%", player.getUsername())
                    .replace("%message%", originalMessage)
                    .replace("%server%", serverAlias)
                    .replace("%reason%", mute.reason())
                    .replace("%end-date%", mute.endDateString())
                    .replace("%moderator%", mute.moderator());

            final String finalProcessed = applyLuckPermsPlaceholders(player, processed, broadcaster);

            if (isExcluded || !player.getCurrentServer().isPresent()) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(finalProcessed));
                return;
            }

            try {
                PlaceholderAPI papi = PlaceholderAPI.getInstance();

                papi.formatPlaceholders(finalProcessed, player.getUniqueId())
                        .completeOnTimeout(finalProcessed, 1, TimeUnit.SECONDS)
                        .thenAccept(finalMsg -> {
                            player.sendMessage(MiniMessage.miniMessage().deserialize(finalMsg));
                        })
                        .exceptionally(ex -> {
                            player.sendMessage(MiniMessage.miniMessage().deserialize(finalProcessed));
                            return null;
                        });

            } catch (Throwable t) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(finalProcessed));
            }
        });
    }

    private String applyLuckPermsPlaceholders(Player player, String text, Broadcaster broadcaster) {
        LuckPermsCompatibilityProvider lp = VMessagePlugin.get().getLuckPermsCompatibilityProvider();
        if (lp == null) return text;

        LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
        String result = text
                .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

        for (Map.Entry<String, String> entry : broadcaster.getMetaPlaceholders().entrySet()) {
            result = result.replace(
                    entry.getKey(),
                    Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("")
            );
        }
        return result;
    }

    @Subscribe
    private void onPlayerLeave(DisconnectEvent e) {
        try {
            VMessagePlugin.get().getBroadcaster().leave(e.getPlayer());
        } catch (Exception ex) {
            VMessagePlugin.get().getLogger().error("Error while broadcasting player leave event: {}", ex.getMessage());
        }
    }

    @Subscribe
    private void onPlayerConnect(ServerPostConnectEvent e) {
        RegisteredServer pre = e.getPreviousServer();
        if (pre == null) {
            VMessagePlugin.get().getBroadcaster().join(e.getPlayer());
        } else {
            VMessagePlugin.get().getBroadcaster().change(e.getPlayer(), pre.getServerInfo().getName());
        }
    }
}