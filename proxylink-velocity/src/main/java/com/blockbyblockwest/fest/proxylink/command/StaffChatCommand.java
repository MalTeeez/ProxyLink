package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Optional;
import java.util.StringJoiner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;

public class StaffChatCommand implements SimpleCommand {

  private final static String PERMISSION = "proxylink.staffchat";

  // Can't say I'm a fan but.. oh well
  private static final char COLOR_CHAR = '\u00A7';
  private final static String SERVER_NAME_FORMAT = "&8[&a%s&8]".replace('&', COLOR_CHAR);
  private final static String FULL_MESSAGE_FORMAT = "&8[&2&lSC&r&8] %s &a%s: %s"
      .replace('&', COLOR_CHAR);
  private final ProxyLinkVelocity plugin;

  public StaffChatCommand(ProxyLinkVelocity plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(SimpleCommand.Invocation invocation) {
    CommandSource commandSource = invocation.source();
    String[] strings = invocation.arguments();
    
    if (strings.length < 1) {
      if (commandSource instanceof Player) {
        commandSource.sendMessage(Component.text("Usage: /staffchat <message>").color(NamedTextColor.RED));
      }
      return;
    }

    StringJoiner joiner = new StringJoiner(" ");
    for (String arg : strings) {
      joiner.add(arg);
    }

    String username;
    String server;

    if (commandSource instanceof Player) {
      Player player = (Player) commandSource;
      username = player.getUsername();
      Optional<ServerConnection> serverConnection = player.getCurrentServer();
      server = serverConnection.map(
          connection -> String.format(SERVER_NAME_FORMAT, connection.getServerInfo().getName()))
          .orElse(" ");
    } else {
      username = "Console";
      server = " ";
    }

    try {
      plugin.getNetworkService()
          .broadcast(String.format(FULL_MESSAGE_FORMAT, server, username, joiner.toString()),
              PERMISSION);
    } catch (ServiceException e) {
      e.printStackTrace();
    }
  }

  @Override
  public boolean hasPermission(SimpleCommand.Invocation invocation) {
    return invocation.source().hasPermission(PERMISSION);
  }

}
