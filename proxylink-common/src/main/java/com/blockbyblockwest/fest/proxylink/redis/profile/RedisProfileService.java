package com.blockbyblockwest.fest.proxylink.redis.profile;

import com.blockbyblockwest.fest.proxylink.exception.ServiceException;
import com.blockbyblockwest.fest.proxylink.profile.Profile;
import com.blockbyblockwest.fest.proxylink.profile.ProfileService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

public class RedisProfileService implements ProfileService {

  private static final long NAME_CACHE_TTL = (int) Duration.ofDays(30).getSeconds();
  private static final long UUID_CACHE_TTL = (int) Duration.ofDays(30).getSeconds();

  private static final Pattern COLON = Pattern.compile(":");

  private final JedisPool jedisPool;

  public RedisProfileService(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
  }

  private Optional<Profile> getProfileFromResponse(UUID uniqueId, String response) {
    if (response != null) {
      String[] split = COLON.split(response);
      return Optional.of(Profile.of(uniqueId, split[0], split[1], split[2]));
    }
    return Optional.empty();
  }

  @Override
  public Optional<Profile> getProfile(UUID uniqueId) throws ServiceException {
    if (uniqueId == null) {
      return Optional.empty();
    }
    try (Jedis jedis = jedisPool.getResource()) {
      String profileResponse = jedis.get(ProfileKey.createCacheKey(uniqueId.toString()));
      return getProfileFromResponse(uniqueId, profileResponse);
    } catch (Exception e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public Map<UUID, Optional<Profile>> getProfiles(Collection<UUID> uuidList)
      throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Map<UUID, Response<String>> responseMap = new HashMap<>();
      Pipeline pipe = jedis.pipelined();

      for (UUID uuid : uuidList) {
        responseMap.put(uuid, pipe.get(ProfileKey.createCacheKey(uuid.toString())));
      }
      pipe.sync();

      Map<UUID, Optional<Profile>> result = new HashMap<>();
      for (Entry<UUID, Response<String>> entry : responseMap.entrySet()) {
        Optional<Profile> output = getProfileFromResponse(entry.getKey(), entry.getValue().get());
        result.put(entry.getKey(), output);
      }
      return result;
    } catch (Exception e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public Optional<Profile> getProfile(String name) throws ServiceException {
    Optional<UUID> optionalUniqueId = getUniqueIdByName(name);
    if (optionalUniqueId.isPresent()) {
      return getProfile(optionalUniqueId.get());
    }
    return Optional.empty();
  }

  @Override
  public List<Optional<Profile>> getProfilesByName(Collection<String> nameList)
      throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      List<Response<String>> reponse = new ArrayList<>();
      Pipeline pipe = jedis.pipelined();

      for (String name : nameList) {
        reponse.add(pipe.get(ProfileKey.createCacheKey(name.toLowerCase())));
      }
      pipe.sync();

      return new ArrayList<>(
          getProfiles(reponse.stream().map(Response::get)
              .map(this::getUniqueIdFromResponse)
              .map(opt -> opt.orElse(null))
              .collect(Collectors.toSet())
          ).values());
    } catch (Exception e) {
      throw new ServiceException(e);
    }
  }

  private Optional<UUID> getUniqueIdFromResponse(String response) {
    return response != null ? Optional.of(UUID.fromString(response)) : Optional.empty();
  }

  @Override
  public Optional<UUID> getUniqueIdByName(String name) throws ServiceException {
    if (name == null) {
      return Optional.empty();
    }
    try (Jedis jedis = jedisPool.getResource()) {
      return getUniqueIdFromResponse(jedis.get(ProfileKey.createCacheKey(name.toLowerCase())));
    } catch (Exception e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public void update(Profile profile) throws ServiceException {
    try (Jedis jedis = jedisPool.getResource()) {
      Transaction transaction = jedis.multi();

      profile.getSkin().ifPresent(
          skin -> transaction.setex(ProfileKey.createCacheKey(profile.getUniqueId().toString()),
              UUID_CACHE_TTL,
              profile.getName() + ":" + skin.getValue() + ":" + skin.getSignature()));

      transaction.setex(ProfileKey.createCacheKey(profile.getName().toLowerCase()), NAME_CACHE_TTL,
          profile.getUniqueId().toString());
      transaction.exec();
    } catch (Exception e) {
      throw new ServiceException(e);
    }
  }

}
