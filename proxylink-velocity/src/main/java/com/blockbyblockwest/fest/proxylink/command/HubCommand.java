package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.ServerType;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.blockbyblockwest.fest.proxylink.models.BackendServer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.Comparator;
import java.util.Optional;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;

public class HubCommand implements SimpleCommand {

  private final ProxyLinkVelocity plugin;

  public HubCommand(ProxyLinkVelocity plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(SimpleCommand.Invocation invocation) {
    CommandSource commandSource = invocation.source();
    String[] strings = invocation.arguments();

    if (commandSource instanceof Player) {
      Player player = (Player) commandSource;
      player.getCurrentServer().ifPresent(connection -> {
        try {
          plugin.getNetworkService().getServer(connection.getServerInfo().getName()).ifPresent(
              backendServer -> {
                if (backendServer.getServerType() == ServerType.HUB) {
                  player.sendMessage(
                      Component.text("You are already connected to a hub!").color(NamedTextColor.RED));
                  return;
                }

                try {
                  Optional<? extends BackendServer> hubServer = plugin.getNetworkService()
                      .getServers().stream()
                      .filter(server -> server.getServerType() == ServerType.HUB)
                      .min(Comparator.comparingInt(BackendServer::getPlayerCount));

                  if (hubServer.isPresent()) {
                    player.sendMessage(
                        Component.text("Connecting you to a hub.. (" + hubServer.get().getId() + ")")
                            .color(NamedTextColor.GREEN));
                    plugin.getProxy().getServer(hubServer.get().getId()).ifPresent(
                        registeredServer -> player.createConnectionRequest(registeredServer)
                            .fireAndForget());
                  } else {
                    player.sendMessage(
                        Component.text("There are no hub servers available!")
                            .color(NamedTextColor.RED));
                  }

                } catch (ServiceException e) {
                  e.printStackTrace();
                }

              });
        } catch (ServiceException e) {
          e.printStackTrace();
        }
      });

    }
  }

}
