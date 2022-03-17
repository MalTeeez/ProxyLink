package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

public class BroadcastCommand implements SimpleCommand {

  private static final char COLOR_CHAR = '\u00A7';

  private final ProxyLinkVelocity plugin;

  public BroadcastCommand(ProxyLinkVelocity plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(SimpleCommand.Invocation invocation) {
    CommandSource commandSource = invocation.source();
    String[] strings = invocation.arguments();
    if (strings.length > 0) {
      try {
        plugin.getNetworkService().broadcast(String.join(" ", strings).replace('&', COLOR_CHAR));
      } catch (ServiceException e) {
        e.printStackTrace();
        commandSource.sendMessage(Component.text("An error occurred", NamedTextColor.RED));
      }
    }
  }

  @Override
  public List<String> suggest(SimpleCommand.Invocation invocation) {
    return new ArrayList<String>();
  }

  @Override
  public boolean hasPermission(SimpleCommand.Invocation invocation) {
    return invocation.source().hasPermission("proxylink.broadcast");
  }

}
