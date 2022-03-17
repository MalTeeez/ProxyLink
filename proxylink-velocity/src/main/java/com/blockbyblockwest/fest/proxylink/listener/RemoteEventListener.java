package com.blockbyblockwest.fest.proxylink.listener;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.event.NetworkBroadcastEvent;
import com.blockbyblockwest.fest.proxylink.event.backendserver.BackendServerRegisterEvent;
import com.blockbyblockwest.fest.proxylink.event.backendserver.BackendServerUnregisterEvent;
import com.blockbyblockwest.fest.proxylink.event.user.UserKickEvent;
import com.blockbyblockwest.fest.proxylink.event.user.UserMessageEvent;
import com.blockbyblockwest.fest.proxylink.event.user.UserSwitchServerRequestEvent;
import com.blockbyblockwest.fest.proxylink.user.MessageType;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class RemoteEventListener {

  private final ProxyLinkVelocity plugin;

  public RemoteEventListener(ProxyLinkVelocity plugin) {
    this.plugin = plugin;
  }

  @Subscribe
  public void onServerRegister(BackendServerRegisterEvent e) {
    plugin.toVelocityServer(e.getBackendServer())
        .ifPresent(found -> plugin.getProxy().unregisterServer(found.getServerInfo()));

    plugin.getLogger().info("Registering {}", e.getBackendServer());
    plugin.getProxy().registerServer(new ServerInfo(e.getBackendServer().getId(),
        new InetSocketAddress(e.getBackendServer().getHost(), e.getBackendServer().getPort())));
  }

  @Subscribe
  public void onServerUnregister(BackendServerUnregisterEvent e) {
    plugin.getLogger().info("Unregistering {}", e.getServerId());
    plugin.getProxy().getServer(e.getServerId())
        .ifPresent(found -> plugin.getProxy().unregisterServer(found.getServerInfo()));

  }

  @Subscribe
  public void onSwitchRequest(UserSwitchServerRequestEvent e) {
    plugin.getLogger()
        .info("Switch Request of user {} to server {}", e.getUniqueId(), e.getToServer());
    plugin.getProxy().getServer(e.getToServer())
        .ifPresent(server -> plugin.getProxy().getPlayer(e.getUniqueId())
            .ifPresent(player -> player.createConnectionRequest(server).fireAndForget()));

  }


  @Subscribe
  public void onUserMessage(UserMessageEvent e) {
    plugin.getLogger().info("Message for {} containing {}", e.getUniqueId(), e.getMessage());
    if (e.getType() == MessageType.COMPONENT) {
      plugin.getProxy().getPlayer(e.getUniqueId())
          .ifPresent(player -> player
              .sendMessage(GsonComponentSerializer.gson().deserialize(e.getMessage())));
    } else if (e.getType() == MessageType.STRING) {
      plugin.getProxy().getPlayer(e.getUniqueId())
          .ifPresent(player -> player
              .sendMessage(LegacyComponentSerializer.legacySection().deserialize(e.getMessage())));
    }
  }

  @Subscribe
  public void onUserKick(UserKickEvent e) {
    plugin.getLogger().info("Kick user {} for reason {}", e.getUniqueId(), e.getReason());
    plugin.getProxy().getPlayer(e.getUniqueId())
        .ifPresent(player -> player
            .disconnect(LegacyComponentSerializer.legacySection().deserialize(e.getReason())));

  }

  @Subscribe
  public void onBroadcast(NetworkBroadcastEvent e) {
    TextComponent message = LegacyComponentSerializer.legacySection().deserialize(e.getMessage());
    for (Player player : plugin.getProxy().getAllPlayers()) {
      if (e.getPermission().isEmpty() || player.hasPermission(e.getPermission())) {
        player.sendMessage(message);
      }
    }
  }

}
