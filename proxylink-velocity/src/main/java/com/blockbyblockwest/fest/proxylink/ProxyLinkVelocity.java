package com.blockbyblockwest.fest.proxylink;

import com.blockbyblockwest.fest.proxylink.command.*;
import com.blockbyblockwest.fest.proxylink.config.Config;
import com.blockbyblockwest.fest.proxylink.event.VelocityEventExecutor;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.blockbyblockwest.fest.proxylink.listener.ProfileUpdateListener;
import com.blockbyblockwest.fest.proxylink.listener.ProxyLinkListener;
import com.blockbyblockwest.fest.proxylink.listener.RemoteEventListener;
import com.blockbyblockwest.fest.proxylink.models.BackendServer;
import com.blockbyblockwest.fest.proxylink.models.LinkedProxyServer;
import com.blockbyblockwest.fest.proxylink.profile.ProfileService;
import com.blockbyblockwest.fest.proxylink.redis.JedisConfig;
import com.blockbyblockwest.fest.proxylink.redis.RedisBackend;
import com.blockbyblockwest.fest.proxylink.redis.RedisNetworkService;
import com.blockbyblockwest.fest.proxylink.redis.profile.RedisProfileService;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.leangen.geantyref.TypeToken;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Plugin(id = "proxylink", name = "ProxyLink", version = "1.1", authors = {"Gabik21"})
public class ProxyLinkVelocity {

  private static ProxyLinkVelocity instance;

  public static ProxyLinkVelocity getInstance() {
    return instance;
  }

  private final RedisBackend redisBackend = new RedisBackend();

  private final ProxyServer proxy;
  private final Path directory;
  private final Logger logger;

  private String serverId;
  private ScheduledTask heartbeatTask;

  private NetworkService networkService;
  private ProfileService profileService;

  private OnlinePlayerNames onlinePlayerNames;

  @Inject
  public ProxyLinkVelocity(ProxyServer proxy,
      @DataDirectory Path directory, Logger logger) {
    this.proxy = proxy;
    this.directory = directory;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    instance = this;

    File configFile = new File(directory.toFile(), "config.conf");
    Config config = new Config(configFile);
    try {
      config.load();
    } catch (IOException e) {
      e.printStackTrace();
    }

    this.serverId = config.getNode("proxyid").getString();
    try {
      ConfigurationNode redisNode = config.getNode("redis");
      redisBackend.initialize(new JedisConfig(redisNode.node("host").getString(),
          redisNode.node("password").getString(), redisNode.node("database").getInt(),
          redisNode.node("port").getInt(), redisNode.node("ssl").getBoolean(),
          redisNode.node("max-pool-size").getInt(),
          redisNode.node("max-pool-idle-size").getInt(),
          redisNode.node("min-pool-idle-size").getInt()));

      networkService = new RedisNetworkService(redisBackend.getJedisPool(),
          new VelocityEventExecutor(proxy.getEventManager()));
      profileService = new RedisProfileService(redisBackend.getJedisPool());

      networkService.initialize();
      logger.info("Proxy ID: {}", serverId);
      for (LinkedProxyServer proxyServer : networkService.getProxyServers()) {
        if (proxyServer.getId().equals(serverId)) {
          throw new ServiceException("Proxy already running with that id");
        }
      }

      // wait for gabiks dc reply
      ConfigurationNode generic = config.getNode("generic");
      List<String> test = generic.node("server-types").getList(TypeToken.get(String.class));
      assert test != null;
      test.forEach(logger::info);

      networkService.removeProxy(serverId);
      networkService.proxyHeartBeat(serverId);

      for (BackendServer server : networkService.getServers()) {
        proxy.registerServer(new ServerInfo(server.getId(),
            new InetSocketAddress(server.getHost(), server.getPort())));
      }

      heartbeatTask = proxy.getScheduler()
          .buildTask(this, this::executeHeartBeat)
          .repeat(30, TimeUnit.SECONDS).schedule();

      onlinePlayerNames = new OnlinePlayerNames(networkService, profileService);

      proxy.getScheduler()
          .buildTask(this, onlinePlayerNames::update)
          .repeat(10, TimeUnit.SECONDS).schedule();

    } catch (ServiceException e) {
      e.printStackTrace();
      System.exit(1);
    } catch (SerializationException e) {
      e.printStackTrace();
    }

    proxy.getEventManager().register(this, new ProxyLinkListener(this, networkService));
    proxy.getEventManager().register(this, new RemoteEventListener(this));
    proxy.getEventManager().register(this, new ProfileUpdateListener(profileService));

    proxy.getCommandManager().register("send", new SendCommand(this));
    proxy.getCommandManager().register("hub", new HubCommand(this));
    proxy.getCommandManager().register("staffchat", new StaffChatCommand(this), "sc");
    proxy.getCommandManager().register("broadcast", new BroadcastCommand(this), "alert");
    proxy.getCommandManager().register("find", new FindCommand(this), "search");

  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent e) {
    if (heartbeatTask != null) {
      heartbeatTask.cancel();
    }
    if (networkService != null) {
      try {
        networkService.removeProxy(serverId);
      } catch (ServiceException serviceException) {
        serviceException.printStackTrace();
      }
      networkService.shutdown();
    }
    redisBackend.shutdown();
  }


  private void executeHeartBeat() {
    try {
      networkService.proxyHeartBeat(serverId);
    } catch (ServiceException e) {
      e.printStackTrace();
    }
  }

  public NetworkService getNetworkService() {
    return networkService;
  }

  public ProfileService getProfileService() {
    return profileService;
  }

  public String getServerId() {
    return serverId;
  }

  public ProxyServer getProxy() {
    return proxy;
  }

  public Logger getLogger() {
    return logger;
  }

  public OnlinePlayerNames getOnlinePlayerNames() {
    return onlinePlayerNames;
  }

  public Optional<RegisteredServer> toVelocityServer(BackendServer backendServer) {
    return getProxy().getServer(backendServer.getId());
  }

  public Optional<? extends BackendServer> findBackendWithLeastPlayers(ServerType type) throws ServiceException {
    return networkService.getServers().stream()
        .filter(server -> server.getServerType() == type)
        .min(Comparator.comparingInt(BackendServer::getPlayerCount));
  }

}
