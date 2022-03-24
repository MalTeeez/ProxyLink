package com.blockbyblockwest.fest.proxylink.event;

import com.blockbyblockwest.fest.proxylink.ProxyLinkBukkit;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ProxyLinkEvent extends Event {

  private static final HandlerList handlerList = new HandlerList();

  private final Object event;

  public ProxyLinkEvent(Object event) {
    super(true);
    this.event = event;
    ProxyLinkBukkit.getInstance().getServer().getOnlinePlayers().forEach(player ->
            player.customName(LegacyComponentSerializer.legacySection().deserialize("test")));
  }

  public Object getEvent() {
    return event;
  }

  @Override
  public HandlerList getHandlers() {
    return handlerList;
  }

  public static HandlerList getHandlerList() {
    return handlerList;
  }

}
