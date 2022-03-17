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
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import ninja.leaping.configurate.ConfigurationNode;
import org.slf4j.Logger;

@Plugin(id = "proxylink", name = "ProxyLink", version = "1.0", authors = {"Gabik21"})
public class ProxyLinkVelocity {

  private static ProxyLinkVelocity instance;

  public static ProxyLinkVelocity getInstance() {
    return instance;
  }

  private final RedisBackend redisBackend = new RedisBackend();

  private final ProxyServer proxy;
  private final CommandManager commandManager;
  private final Path directory;
  private final Logger logger;

  private String serverId;
  private ScheduledTask heartbeastTask;

  private NetworkService networkService;
  private ProfileService profileService;

  private OnlinePlayerNames onlinePlayerNames;

  @Inject
  public ProxyLinkVelocity(ProxyServer proxy, CommandManager commandManager,
      @DataDirectory Path directory, Logger logger) {
    this.proxy = proxy;
    this.commandManager = commandManager;
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
      redisBackend.initialize(new JedisConfig(redisNode.getNode("host").getString(),
          redisNode.getNode("password").getString(), redisNode.getNode("database").getInt(),
          redisNode.getNode("port").getInt(), redisNode.getNode("ssl").getBoolean(),
          redisNode.getNode("max-pool-size").getInt(),
          redisNode.getNode("max-pool-idle-size").getInt(),
          redisNode.getNode("min-pool-idle-size").getInt()));

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

      networkService.removeProxy(serverId);
      networkService.proxyHeartBeat(serverId);

      for (BackendServer server : networkService.getServers()) {
        proxy.registerServer(new ServerInfo(server.getId(),
            new InetSocketAddress(server.getHost(), server.getPort())));
      }

      heartbeastTask = proxy.getScheduler()
          .buildTask(this, this::executeHeartBeat)
          .repeat(30, TimeUnit.SECONDS).schedule();

      onlinePlayerNames = new OnlinePlayerNames(networkService, profileService);

      proxy.getScheduler()
          .buildTask(this, onlinePlayerNames::update)
          .repeat(10, TimeUnit.SECONDS).schedule();

    } catch (ServiceException e) {
      e.printStackTrace();
      System.exit(1);
    }

    proxy.getEventManager().register(this, new ProxyLinkListener(this, networkService));
    proxy.getEventManager().register(this, new RemoteEventListener(this));
    proxy.getEventManager().register(this, new ProfileUpdateListener(profileService));

    commandManager.register("send", new SendCommand(this));
    commandManager.register("hub", new HubCommand(this));
    commandManager.register("staffchat", new StaffChatCommand(this), "sc");
    commandManager.register("broadcast", new BroadcastCommand(this), "alert");
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent e) {
    if (heartbeastTask != null) {
      heartbeastTask.cancel();
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

  public Optional<? extends BackendServer> findHubWithLeastPlayers() throws ServiceException {
    return networkService.getServers().stream()
        .filter(server -> server.getServerType() == ServerType.HUB)
        .min(Comparator.comparingInt(BackendServer::getPlayerCount));
  }

}
