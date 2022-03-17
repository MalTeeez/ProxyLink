package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;

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
              targetServer = plugin.getProxy().getServer(strings[1]).orElse(null);
            } else if (commandSource instanceof Player) {
              targetServer = ((Player) commandSource).getCurrentServer().map(ServerConnection::getServer)
                  .orElse(null);
            }
            if (targetServer != null) {
              commandSource.sendMessage(Component.text("Trying to send them over...", NamedTextColor.GREEN));
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
        }
      });
    } catch (ServiceException e) {
      e.printStackTrace();
      commandSource.sendMessage(Component.text("An error occurred", NamedTextColor.RED));
    }
  }

  @Override
  public List<String> suggest(SimpleCommand.Invocation invocation) {
    String[] strings = invocation.arguments();
    if (strings.length != 1 || strings[0].length() < 2) {
      return Collections.emptyList();
    }
    return plugin.getOnlinePlayerNames().getNames()
        .stream()
        .filter(name -> name.regionMatches(true, 0, strings[0], 0, strings[0].length()))
        .collect(Collectors.toList());
  }

  @Override
  public boolean hasPermission(SimpleCommand.Invocation invocation) {
    return invocation.source().hasPermission("proxylink.sendto");
  }

}
