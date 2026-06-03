package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class CacheServiceTest {

    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired CacheService cacheService;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void cacheMiss_callsSupplierAndCachesResult() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> supplier = () -> { calls.incrementAndGet(); return "result"; };

        String v1 = cacheService.get("acme", "repo", "svc-001", "facts:class", "hash1",
                supplier, String.class);
        String v2 = cacheService.get("acme", "repo", "svc-001", "facts:class", "hash1",
                supplier, String.class);

        assertThat(v1).isEqualTo("result");
        assertThat(v2).isEqualTo("result");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidate_removesAllKeysForService() {
        cacheService.get("acme", "repo", "svc-001", "facts:class",    "h1", () -> "a", String.class);
        cacheService.get("acme", "repo", "svc-001", "graph:reach",    "h2", () -> "b", String.class);
        cacheService.get("acme", "repo", "svc-002", "facts:outbound", "h3", () -> "c", String.class);

        cacheService.invalidate("acme", "repo", "svc-001");

        AtomicInteger calls = new AtomicInteger(0);
        cacheService.get("acme", "repo", "svc-001", "facts:class", "h1",
                () -> { calls.incrementAndGet(); return "a"; }, String.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
