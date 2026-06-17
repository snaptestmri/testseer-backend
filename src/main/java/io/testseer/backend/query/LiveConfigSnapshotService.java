package io.testseer.backend.query;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiveConfigSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(LiveConfigSnapshotService.class);

    public enum LiveStatus { PASS, FAIL, UNKNOWN }

    public record OverlayContext(String liveConfigStatus, String liveConfigEnv) {}

    private final boolean enabled;
    private final JdbcClient liveDb;
    private final MessagingRulePackLoader rulePackLoader;
    private final String defaultEnvLane;
    private final long cacheTtlMillis;
    private final ConcurrentHashMap<String, CachedConfig> cache = new ConcurrentHashMap<>();

    public LiveConfigSnapshotService(
            MessagingRulePackLoader rulePackLoader,
            @Value("${testseer.live-config.enabled:false}") boolean enabled,
            @Value("${testseer.live-config.jdbc-url:}") String jdbcUrl,
            @Value("${testseer.live-config.username:}") String username,
            @Value("${testseer.live-config.password:}") String password,
            @Value("${testseer.live-config.env-lane-default:pdn}") String defaultEnvLane,
            @Value("${testseer.live-config.cache-ttl-seconds:300}") int cacheTtlSeconds) {
        this.rulePackLoader = rulePackLoader;
        this.defaultEnvLane = defaultEnvLane;
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;
        this.enabled = enabled && jdbcUrl != null && !jdbcUrl.isBlank();
        this.liveDb = this.enabled ? JdbcClient.create(buildDataSource(jdbcUrl, username, password)) : null;
        if (enabled && !this.enabled) {
            log.warn("Live config enabled but jdbc-url missing — overlay disabled");
        }
    }

    public OverlayContext overlayContext(String env) {
        String lane = env != null && !env.isBlank() ? env : defaultEnvLane;
        if (!enabled) {
            return new OverlayContext("DISABLED", lane);
        }
        return new OverlayContext("OK", lane);
    }

    public List<MessagingFlowService.FlowGateView> overlayGates(
            String env, List<MessagingFlowService.FlowGateView> gates, boolean refresh) {
        OverlayContext ctx = overlayContext(env);
        if ("DISABLED".equals(ctx.liveConfigStatus())) {
            return gates.stream().map(g -> withUnknown(g, null)).toList();
        }

        Map<String, String> configValues = fetchConfigValues(gates, env, refresh);
        Instant snapshotAt = Instant.now();
        return gates.stream()
                .map(g -> overlayGate(g, configValues, snapshotAt))
                .toList();
    }

    private MessagingFlowService.FlowGateView overlayGate(
            MessagingFlowService.FlowGateView gate,
            Map<String, String> configValues,
            Instant snapshotAt) {
        String configKey = resolveConfigKey(gate.gateKey());
        if (configKey == null) {
            return withUnknown(gate, snapshotAt);
        }
        String liveValue = configValues.get(configKey);
        if (liveValue == null) {
            return withUnknown(gate, snapshotAt);
        }
        boolean redact = isRedacted(gate.gateKey());
        String displayValue = redact ? "***" : liveValue;
        LiveStatus status = evaluate(gate.requiredValue(), liveValue);
        return gate.withLive(displayValue, status.name(), snapshotAt, "SYSTEM_CONFIGURATION");
    }

    static LiveStatus evaluate(String requiredValue, String liveValue) {
        if (requiredValue == null || liveValue == null) {
            return LiveStatus.UNKNOWN;
        }
        String required = requiredValue.trim();
        String live = liveValue.trim();
        if (required.equalsIgnoreCase("true") || required.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(live) == Boolean.parseBoolean(required)
                    ? LiveStatus.PASS : LiveStatus.FAIL;
        }
        return live.equals(required) ? LiveStatus.PASS : LiveStatus.FAIL;
    }

    private Map<String, String> fetchConfigValues(
            List<MessagingFlowService.FlowGateView> gates, String env, boolean refresh) {
        List<String> keys = gates.stream()
                .map(MessagingFlowService.FlowGateView::gateKey)
                .map(this::resolveConfigKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return Map.of();

        String cacheKey = (env != null ? env : defaultEnvLane) + ":" + String.join(",", keys);
        if (!refresh) {
            CachedConfig cached = cache.get(cacheKey);
            if (cached != null && !cached.isExpired(cacheTtlMillis)) {
                return cached.values();
            }
        }

        Map<String, String> fetched = new LinkedHashMap<>();
        try {
            for (String key : keys) {
                liveDb.sql("""
                        SELECT config_key, config_value FROM system_configuration
                        WHERE config_key = :key
                        """)
                        .param("key", key)
                        .query((rs, row) -> {
                            fetched.put(rs.getString("config_key"), rs.getString("config_value"));
                            return null;
                        })
                        .optional();
            }
            cache.put(cacheKey, new CachedConfig(fetched, Instant.now()));
        } catch (Exception ex) {
            log.warn("Live config fetch failed for env {}: {}", env, ex.getMessage());
        }
        return fetched;
    }

    private String resolveConfigKey(String gateKey) {
        if (gateKey == null || gateKey.isBlank()) return null;
        MessagingRulePack.GateKeyAliasRule alias = rulePackLoader.getRulePack().gateKeyAliases().get(gateKey);
        if (alias != null && alias.configKey() != null) {
            return alias.configKey();
        }
        return gateKey;
    }

    private boolean isRedacted(String gateKey) {
        MessagingRulePack.GateKeyAliasRule alias = rulePackLoader.getRulePack().gateKeyAliases().get(gateKey);
        return alias != null && alias.redact();
    }

    private static MessagingFlowService.FlowGateView withUnknown(
            MessagingFlowService.FlowGateView gate, Instant snapshotAt) {
        return gate.withLive(null, LiveStatus.UNKNOWN.name(), snapshotAt, null);
    }

    private static DataSource buildDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null && !username.isBlank()) config.setUsername(username);
        if (password != null && !password.isBlank()) config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setReadOnly(true);
        return new HikariDataSource(config);
    }

    private record CachedConfig(Map<String, String> values, Instant fetchedAt) {
        boolean isExpired(long ttlMillis) {
            return Instant.now().isAfter(fetchedAt.plusMillis(ttlMillis));
        }
    }
}
