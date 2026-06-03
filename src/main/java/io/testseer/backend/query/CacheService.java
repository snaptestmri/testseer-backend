package io.testseer.backend.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public CacheService(StringRedisTemplate redis, ObjectMapper mapper,
                        @Value("${testseer.cache.ttl-seconds:3600}") int ttlSeconds) {
        this.redis  = redis;
        this.mapper = mapper;
        this.ttl    = Duration.ofSeconds(ttlSeconds);
    }

    public <T> T get(String orgId, String repo, String serviceId,
                     String queryType, String paramsHash,
                     Supplier<T> loader, Class<T> type) {
        String key = key(orgId, repo, serviceId, queryType, paramsHash);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return mapper.readValue(cached, type);
        } catch (Exception ex) {
            log.warn("Redis read failed for key {}: {}", key, ex.getMessage());
        }

        T value = loader.get();

        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}: {}", key, ex.getMessage());
        }
        return value;
    }

    public void invalidate(String orgId, String repo, String serviceId) {
        String pattern = "testseer:" + orgId + ":" + repo + ":" + serviceId + ":*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.debug("Invalidated {} cache keys for {}/{}/{}", keys.size(), orgId, repo, serviceId);
        }
    }

    private static String key(String orgId, String repo, String serviceId,
                               String queryType, String paramsHash) {
        return "testseer:" + orgId + ":" + repo + ":" + serviceId + ":" + queryType + ":" + paramsHash;
    }
}
