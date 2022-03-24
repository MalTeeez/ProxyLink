package com.blockbyblockwest.fest.proxylink.redis;

import com.blockbyblockwest.fest.proxylink.EventExecutor;
import com.blockbyblockwest.fest.proxylink.NetworkService;
import com.blockbyblockwest.fest.proxylink.ServerType;
import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.blockbyblockwest.fest.proxylink.models.BackendServer;
import com.blockbyblockwest.fest.proxylink.models.LinkedProxyServer;
import com.blockbyblockwest.fest.proxylink.redis.models.BackendServerResponse;
import com.blockbyblockwest.fest.proxylink.redis.models.RedisBackendServer;
import com.blockbyblockwest.fest.proxylink.redis.models.RedisLinkedProxyServer;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.LocalNetworkState;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.NetworkPubSub;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.BackendServerRegisterPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.BackendServerUnregisterPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.BackendServerUpdatePlayerCountPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.NetworkBroadcastPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.UserDisconnectPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.UserKickPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.UserMessagePacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.UserSwitchServerPacket;
import com.blockbyblockwest.fest.proxylink.redis.pubsub.packet.UserSwitchServerRequestPacket;
import com.blockbyblockwest.fest.proxylink.user.MessageType;
import com.blockbyblockwest.fest.proxylink.util.TimeUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

public class RedisNetworkService implements NetworkService {

  private final LocalNetworkState localNetworkState = new LocalNetworkState();
  private final NetworkPubSub pubSub;
  private final JedisPool jedisPool;

  private boolean highFrequencyPackets = true;

  public RedisNetworkService(JedisPool jedisPool, EventExecutor eventExecutor) {
    this.jedisPool = jedisPool;
    pubSub = new NetworkPubSub(eventExecutor, localNetworkState, this);
  }

  @Override
  public void initialize() {
    pubSub.registerPackets(highFrequencyPackets);
    pubSub.start(jedisPool);
  }

  @Override
  public void shutdown() {
    pubSub.teardown();
  }

  @Override
  public void disableHighFrequencyPackets() {
    highFrequencyPackets = false;
  }

  @Override
  public Set<LinkedProxyServer> getProxyServers() throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      return getProxyServers(jedis);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  private Set<LinkedProxyServer> getProxyServers(Jedis jedis) {
    // Timeout aware
    return jedis
        .zrangeByScore(NetworkKey.PROXY_SET, System.currentTimeMillis(), Double.POSITIVE_INFINITY)
        .stream()
        .map(id -> new RedisLinkedProxyServer(id, jedisPool)).collect(Collectors.toSet());
  }

  @Override
  public Set<UUID> getOnlineUsers() throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Set<LinkedProxyServer> proxies = getProxyServers(jedis);
      return jedis.sunion(
          proxies.stream().map(NetworkKey::getProxyPlayers).toArray(String[]::new)
      ).stream().map(UUID::fromString).collect(Collectors.toSet());
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public int getOnlineUserCount() throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Set<LinkedProxyServer> proxies = getProxyServers(jedis);
      if (proxies.size() > 1) { // If we got more than one proxy we benefit from pipelining
        Pipeline pipe = jedis.pipelined();

        List<Response<Long>> responses = proxies.stream()
            .map(NetworkKey::getProxyPlayers)
            .map(pipe::scard)
            .collect(Collectors.toList());
        pipe.sync();

        return responses.stream()
            .map(Response::get)
            .mapToInt(Math::toIntExact)
            .sum();
      } else if (!proxies.isEmpty()) {
        return Math.toIntExact(jedis.scard(NetworkKey.getProxyPlayers(proxies.iterator().next())));
      } else {
        return 0; // Without proxies there can't be anyone online
      }
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public boolean isUserOnline(UUID uniqueId) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Set<LinkedProxyServer> proxies = getProxyServers(jedis);
      if (proxies.size() > 1) { // If we got more than one proxy we benefit from pipelining
        Pipeline pipe = jedis.pipelined();

        List<Response<Boolean>> responses = proxies.stream()
            .map(NetworkKey::getProxyPlayers)
            .map(proxyPlayerSet -> pipe.sismember(proxyPlayerSet, uniqueId.toString()))
            .collect(Collectors.toList());
        pipe.sync();

        return responses.stream().anyMatch(Response::get);
      } else if (!proxies.isEmpty()) {
        return jedis.sismember(NetworkKey.getProxyPlayers(proxies.iterator().next()),
            uniqueId.toString());
      } else {
        return false; // No proxies -> player offline for sure
      }
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  private Set<String> getServerIds(Jedis jedis) throws ServiceException {
    return jedis.zrangeByScore(NetworkKey.SERVER_SET, System.currentTimeMillis(),
            Double.POSITIVE_INFINITY);
  }

  private Collection<? extends BackendServer> getServerDatas(Jedis jedis, Collection<String> ids) {

    Collection<BackendServerResponse> responseMap = new ArrayList<>(ids.size());

    Pipeline pipe = jedis.pipelined();

    for (String id : ids) {
      String primKey = NetworkKey.getServerKey(id);

      Response<Map<String, String>> mapResponse = pipe.hgetAll(primKey);
      responseMap.add(new BackendServerResponse(id, mapResponse));
    }

    pipe.sync();

    Collection<RedisBackendServer> result = responseMap.stream()
        .map(BackendServerResponse::toServer)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toList());

    for (RedisBackendServer server : result) {
      localNetworkState.getServerInfo().put(server.getId(), server);
    }

    return result;
  }

  @Override
  public Collection<? extends BackendServer> getServers() throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Set<String> serverIds = getServerIds(jedis);

      if (localNetworkState.getServerInfo().size() == serverIds.size()) { // Assume our cache good
        return localNetworkState.getServerInfo().values();
      }

      return getServerDatas(jedis, serverIds);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public Optional<BackendServer> getServer(String id) throws ServiceException {
    RedisBackendServer server = localNetworkState.getServerInfo(id);
    if (server != null) {
      return Optional.of(server);
    }

    try (Jedis jedis = jedisPool.getResource()) {
      String serverKey = NetworkKey.getServerKey(id);
      Map<String, String> serverInfo = jedis.hgetAll(serverKey);

      if (!serverInfo.isEmpty()) {
        ServerType serverType = ServerType.valueOf(serverInfo.get(NetworkKey.SERVER_TYPE));
        server = new RedisBackendServer(id, serverType, serverInfo.get(NetworkKey.SERVER_HOST),
            Integer.parseInt(serverInfo.get(NetworkKey.SERVER_PORT)),
            Integer.parseInt(serverInfo.get(NetworkKey.SERVER_MAX_PLAYER_COUNT)));
        server.setPlayerCount(Integer.parseInt(serverInfo.get(NetworkKey.SERVER_PLAYER_COUNT)));

        // Update local state
        localNetworkState.getServerInfo().put(id, server);

        return Optional.of(server);
      }
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }

    return Optional.empty();
  }

  private static final int PROXY_TIMEOUT_MILLIS = 180 * 1000;
  private static final int SERVER_TIMEOUT_MILLIS = 120 * 1000;

  @Override
  public void proxyHeartBeat(String id) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.zadd(NetworkKey.PROXY_SET, System.currentTimeMillis() + PROXY_TIMEOUT_MILLIS, id);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void removeProxy(String id) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Transaction multi = jedis.multi();
      multi.zrem(NetworkKey.PROXY_SET, id);
      multi.del(NetworkKey.getProxyPlayers(id));
      multi.exec();
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void serverHeartBeat(String id, int playerCount) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Pipeline pipe = jedis.pipelined();
      pipe.zadd(NetworkKey.SERVER_SET, System.currentTimeMillis() + SERVER_TIMEOUT_MILLIS, id);
      pipe.hset(NetworkKey.getServerKey(id), NetworkKey.SERVER_PLAYER_COUNT,
          String.valueOf(playerCount));
      new BackendServerUpdatePlayerCountPacket(id, playerCount).publish(pipe);
      pipe.sync();
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void removeServer(String id) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Transaction multi = jedis.multi();
      multi.zrem(NetworkKey.SERVER_SET, id);
      multi.del(NetworkKey.getServerKey(id));
      new BackendServerUnregisterPacket(id).publish(multi);
      multi.exec();
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public BackendServer registerServer(String id, ServerType serverType, String host, int port,
      int maxPlayerCount) throws ServiceException {
    Double lastBeat;
    try (Jedis jedis = jedisPool.getResource()) {
      lastBeat = jedis.zscore(NetworkKey.SERVER_SET, id);
    }

    if (lastBeat != null) {
      if (TimeUtil.hasPassed(lastBeat.longValue(),
          Duration.ofSeconds(10))) { // 10 seconds of grace for slow responding server
        removeServer(id);
      } else {
        throw new ServiceException("Server already registered");
      }
    }

    try (Jedis jedis = jedisPool.getResource()) {
      Transaction multi = jedis.multi();
      String primKey = NetworkKey.getServerKey(id);

      multi.hset(primKey, NetworkKey.SERVER_TYPE, serverType.toString());
      multi.hset(primKey, NetworkKey.SERVER_HOST, host);
      multi.hset(primKey, NetworkKey.SERVER_PORT, String.valueOf(port));
      multi.hset(primKey, NetworkKey.SERVER_MAX_PLAYER_COUNT, String.valueOf(maxPlayerCount));
      multi.hset(primKey, NetworkKey.SERVER_PLAYER_COUNT, String.valueOf(0));

      RedisBackendServer serverData = new RedisBackendServer(id, serverType, host, port,
          maxPlayerCount);
      new BackendServerRegisterPacket(serverData).publish(multi);

      multi.exec();
      return serverData;
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public Optional<String> getServerIdOfUser(UUID uniqueId) throws ServiceException {
    String server = localNetworkState.getUserServerMap().get(uniqueId);
    if (server != null) {
      return Optional.of(server);
    }
    try (Jedis jedis = jedisPool.getResource()) {
      server = jedis.get(NetworkKey.getUserServer(uniqueId));
      if (server != null) {
        localNetworkState.getUserServerMap().put(uniqueId, server);
      }
      return Optional.ofNullable(server);
    } catch (JedisException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public void connectUserToProxy(UUID uniqueId, String proxyId) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.sadd(NetworkKey.getProxyPlayers(proxyId), uniqueId.toString());
    } catch (JedisException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public void disconnectUser(UUID uniqueId, String proxyId) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Transaction multi = jedis.multi();
      multi.srem(NetworkKey.getProxyPlayers(proxyId), uniqueId.toString());
      multi.del(NetworkKey.getUserServer(uniqueId));
      new UserDisconnectPacket(uniqueId).publish(multi);
      multi.exec();
    } catch (JedisException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public void switchServer(UUID uniqueId, String toServerId) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Transaction multi = jedis.multi();
      multi.set(NetworkKey.getUserServer(uniqueId), toServerId);
      new UserSwitchServerPacket(uniqueId, toServerId).publish(multi);
      multi.exec();
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void sendUserToServer(UUID uniqueId, String toServer) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      new UserSwitchServerRequestPacket(uniqueId, toServer).publish(jedis);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void kickUser(UUID uniqueId, String reason) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      new UserKickPacket(uniqueId, reason).publish(jedis);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void sendMessageToUser(UUID uniqueId, String message, MessageType type)
      throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      new UserMessagePacket(uniqueId, message, type).publish(jedis);
    } catch (JedisException ex) {
      throw new ServiceException(ex);
    }
  }

  @Override
  public void broadcast(String message, String permission) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      new NetworkBroadcastPacket(message, permission).publish(jedis);
    } catch (JedisException e) {
      throw new ServiceException(e);
    }
  }

}
