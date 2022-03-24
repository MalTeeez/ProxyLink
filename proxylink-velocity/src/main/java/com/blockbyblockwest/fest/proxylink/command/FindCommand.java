package com.blockbyblockwest.fest.proxylink.command;

import com.blockbyblockwest.fest.proxylink.ProxyLinkVelocity;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.blockbyblockwest.fest.proxylink.models.BackendServer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static net.kyori.adventure.text.event.HoverEvent.showText;

public class FindCommand implements SimpleCommand {

    private final ProxyLinkVelocity plugin;

    public FindCommand(ProxyLinkVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull Invocation invocation) {
      CommandSource commandSource = invocation.source();
      String[] strings = invocation.arguments();
        try {
          plugin.getProfileService().getProfile(strings[0]).ifPresent(profile -> {
            try {
              if (plugin.getNetworkService().isUserOnline(profile.getUniqueId())) {
                if (plugin.getNetworkService().getServerIdOfUser(profile.getUniqueId()).isPresent()) {
                  if (commandSource instanceof Player) {
                    String fromServer = ((Player) commandSource).getCurrentServer().map(ServerConnection::getServerInfo)
                      .map(ServerInfo::getName).orElse("void");
                    plugin.getNetworkService()
                     .getServer(plugin.getNetworkService().getServerIdOfUser(profile.getUniqueId())
                     .orElse(null)).ifPresent(backendServer -> commandSource.sendMessage(
                       formatMessage("Found player "+profile.getName()+" at: ").append(appendServer(backendServer, profile.getName(), fromServer))));
                    commandSource.playSound(Sound.sound(Key.key("minecraft:entity.experience_orb.pickup"),Sound.Source.MASTER,0.2f, 1.0f));
                  } else {
                    plugin.getNetworkService()
                      .getServer(plugin.getNetworkService().getServerIdOfUser(profile.getUniqueId())
                      .orElse(null)).ifPresent(backendServer -> commandSource.sendMessage(Component.text("Found player "+profile.getName()+" at: "
                        +backendServer.getServerType().name()).color(NamedTextColor.GREEN)));
                  }
                } else {
                    commandSource.sendMessage(Component.text("Could not find "+profile.getName()+"'s server, try again later", NamedTextColor.RED));
                }
            } else {
              commandSource.sendMessage(Component.text("Could not find player "+profile.getName(), NamedTextColor.RED));
            }
            } catch (ServiceException e) {
              e.printStackTrace();
            }
            });
        } catch (ServiceException e) {
            e.printStackTrace();
        }
    }

    private TextComponent formatMessage(String message) {
        TextComponent targetText = Component.text(message);
        targetText = targetText.color(NamedTextColor.GREEN);
        return targetText;
    }

    private TextComponent appendServer(BackendServer server, String target,String currServer) {
        TextComponent targetServer = Component.text(server.getId());
        ClickEvent action;
        TextComponent hoverText;
        TextComponent ptc;
        NamedTextColor color;
        int targetOnline = server.getPlayerCount();
        ptc = Component.text(targetOnline);
        if (targetOnline!=1) {
            ptc = ptc.append(Component.text(" players online"));}
        else {
            ptc = ptc.append(Component.text(" player online"));}
        if (server.getId().equals(currServer)) {
            color = NamedTextColor.YELLOW;
            action = ClickEvent.runCommand("/tp "+target);
            hoverText = Component.text("Click to teleport to "+target);
        } else {
            color = NamedTextColor.GRAY;
            action = ClickEvent.runCommand("/server "+server.getId());
            hoverText = Component.text("Click to connect to "+target+"'s server");
        }
        ptc = ptc.color(NamedTextColor.DARK_GRAY);
        hoverText = hoverText.color(NamedTextColor.DARK_AQUA);
        targetServer = targetServer.color(color).clickEvent(action)
                .hoverEvent(showText(hoverText.append(Component.newline()).append(ptc)));
        return targetServer;
    }
    @Override
    public List<String> suggest(Invocation invocation) {
        List<String> currentArgs = List.of(invocation.arguments());

        if (currentArgs.size() == 0) {
            return new ArrayList<>(plugin.getOnlinePlayerNames().getNames());
        }
    return null;
    }


    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("proxylink.find");
    }
}
