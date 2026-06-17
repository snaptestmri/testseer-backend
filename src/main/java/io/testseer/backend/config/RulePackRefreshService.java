package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Hot-reloads messaging rule pack when file changes; exposes content hash for API responses. */
@Component
public class RulePackRefreshService {

    private static final Logger log = LoggerFactory.getLogger(RulePackRefreshService.class);

    private final MessagingRulePackLoader loader;
    private final Resource rulePackResource;
    private volatile String rulePackHash = "empty";
    private volatile long lastModified = -1L;

    public RulePackRefreshService(
            MessagingRulePackLoader loader,
            @Value("${testseer.messaging.rule-pack-path:file:../config/rule-packs/quotient-messaging.yml}")
            Resource rulePackResource) {
        this.loader = loader;
        this.rulePackResource = rulePackResource;
        refreshIfChanged();
    }

    public String rulePackHash() {
        return rulePackHash;
    }

    @Scheduled(fixedDelayString = "${testseer.rule-pack.reload-interval-ms:60000}")
    public void scheduledRefresh() {
        refreshIfChanged();
    }

    public synchronized void refreshIfChanged() {
        try {
            long modified = resolveLastModified();
            if (modified == lastModified && !"empty".equals(rulePackHash)) {
                return;
            }
            String newHash = computeHash();
            loader.reload();
            lastModified = modified;
            rulePackHash = newHash;
            log.info("Reloaded messaging rule pack (hash={})", rulePackHash);
        } catch (Exception ex) {
            log.warn("Rule pack refresh skipped: {}", ex.getMessage());
        }
    }

    private long resolveLastModified() throws Exception {
        if (rulePackResource.isFile()) {
            return rulePackResource.getFile().lastModified();
        }
        String path = System.getenv("TESTSEER_RULE_PACK");
        if (path != null && Files.isRegularFile(Path.of(path))) {
            return Files.getLastModifiedTime(Path.of(path)).toMillis();
        }
        return System.currentTimeMillis();
    }

    private String computeHash() throws Exception {
        try (InputStream in = openStream()) {
            if (in == null) return "empty";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        }
    }

    private InputStream openStream() throws Exception {
        if (rulePackResource.exists()) {
            return rulePackResource.getInputStream();
        }
        String path = System.getenv("TESTSEER_RULE_PACK");
        if (path != null && Files.isRegularFile(Path.of(path))) {
            return Files.newInputStream(Path.of(path));
        }
        return null;
    }
}
