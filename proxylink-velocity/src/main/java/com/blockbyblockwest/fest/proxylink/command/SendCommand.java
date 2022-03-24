package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.ServerType;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.google.common.collect.ImmutableList;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import static com.blockbyblockwest.fest.proxylink.ServerType.getServerType;

public class SendCommand implements SimpleCommand {

  private final ProxyLinkVelocity plugin;

  public SendCommand(ProxyLinkVelocity plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(SimpleCommand.Invocation invocation) {
    CommandSource commandSource = invocation.source();
    String[] strings = invocation.arguments();    
    
    try {
      plugin.getProfileService().getProfile(strings[0]).ifPresent(profile -> {
        try {
          if (plugin.getNetworkService().isUserOnline(profile.getUniqueId())) {
            RegisteredServer targetServer = null;
            if (strings.length > 1) {
              targetServer = getTargetServer(strings[1]);
            } else if (commandSource instanceof Player) {
              targetServer = ((Player) commandSource).getCurrentServer().map(ServerConnection::getServer)
                  .orElse(null);
            }
            if (targetServer != null) {
              commandSource.sendMessage(Component.text("Trying to send them over to "+targetServer.getServerInfo().getName()
                      +", which currently has "+targetServer.getPlayersConnected().size()+" connected player(s)", NamedTextColor.GREEN));
              plugin.getNetworkService().getUser(profile.getUniqueId())
                  .sendToServer(targetServer.getServerInfo().getName());
            } else {
              commandSource.sendMessage(Component.text("No target server found", NamedTextColor.RED));
            }
          } else {
            commandSource.sendMessage(Component.text("Player is not online", NamedTextColor.RED));
          }
        } catch (ServiceException e) {
          e.printStackTrace();
          commandSource.sendMessage(Component.text("An error occurred", NamedTextColor.RED));
          commandSource.sendMessage(Component.text("Usage: /send <player>/\"all\" <server>").color(NamedTextColor.RED));
        }
      });
      if ((strings[0].equalsIgnoreCase("all")) || Objects.equals(strings[0], "@a")) {
        RegisteredServer targetServer = null;
        try {
          if (commandSource instanceof Player) {
            RegisteredServer fromServer = ((Player) commandSource).getCurrentServer().map(ServerConnection::getServer).orElse(null);
            if (strings.length > 1) {
              targetServer = getTargetServer(strings[1]);
            }
            if (targetServer != null) {
              if (fromServer != null) {
                Collection<Player> prePlayers = fromServer.getPlayersConnected();
                commandSource.sendMessage(Component.text("Trying to send "
                        +prePlayers.size()+"player(s) to "+targetServer.getServerInfo().getName()
                        +", which currently has "+targetServer.getPlayersConnected().size()+" connected player(s)", NamedTextColor.GREEN));
                for (Player player : prePlayers) {
                  plugin.getNetworkService().getUser(player.getUniqueId())
                          .sendToServer(targetServer.getServerInfo().getName());
                  commandSource.sendMessage(Component.text("Trying to send "+player.getUsername()+"...", NamedTextColor.GREEN));
                }
              } else {
                  commandSource.sendMessage(Component.text("No source server found", NamedTextColor.RED));
              }
            } else {
              commandSource.sendMessage(Component.text("No target server found", NamedTextColor.RED));
            }
          } else {
            commandSource.sendMessage(Component.text("Player is not online", NamedTextColor.RED));
          }
        } catch (ServiceException e) {
          e.printStackTrace();
          commandSource.sendMessage(Component.text("An error occurred", NamedTextColor.RED));
          commandSource.sendMessage(Component.text("Usage: /send <player>/\"all\" <server>").color(NamedTextColor.RED));
        }
      }
    } catch (ServiceException e) {
      e.printStackTrace();
      commandSource.sendMessage(Component.text("An error occurred", NamedTextColor.RED));
      commandSource.sendMessage(Component.text("Usage: /send <player>/\"all\" <server>").color(NamedTextColor.RED));
    }
  }

  private RegisteredServer getTargetServer(String string) throws ServiceException {
    final RegisteredServer[] targetServer = {null};
  if (getServerType(string)!=null) {
    plugin.findBackendWithLeastPlayers(getServerType(string))
            .flatMap(plugin::toVelocityServer)
            .ifPresent(registeredServer -> targetServer[0] = registeredServer);
  } else if (plugin.getProxy().getServer(string).orElse(null)!= null) {
    targetServer[0] = plugin.getProxy().getServer(string).orElse(null);
  }
  return targetServer[0];
  }

  @Override
  public List<String> suggest(SimpleCommand.Invocation invocation) {
    List<String> currentArgs = List.of(invocation.arguments());
    List<String> arg = new ArrayList<>();


    // please dont be laggy
    if (currentArgs.size() == 0) {
      arg.add("all");
      arg.addAll(plugin.getOnlinePlayerNames().getNames());
      return arg;
    }

    if (currentArgs.size() > 1) {
      for (RegisteredServer server : plugin.getProxy().getAllServers()) {
        arg.add(server.getServerInfo().getName());
        for (ServerType st : ServerType.values()) {
          arg.add(st.name());
        }
      }
      return arg;
    }

    return ImmutableList.of();
  }

  @Override
  public boolean hasPermission(SimpleCommand.Invocation invocation) {
    return invocation.source().hasPermission("proxylink.sendto");
  }

}
