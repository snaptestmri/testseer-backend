package io.testseer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "testseer.maven")
public class MavenProperties {

    private boolean treeResolutionEnabled = true;
    /** When {@code /admin/index/local} omits {@code mavenTreeResolution}, use this for bulk workspace scripts. */
    private boolean bulkIndexTreeResolutionEnabled = false;
    private int treeTimeoutSeconds = 120;
    private int maxModulesPerIndex = 50;
    private int treeParallelism = 4;

    public boolean isTreeResolutionEnabled() {
        return treeResolutionEnabled;
    }

    public void setTreeResolutionEnabled(boolean treeResolutionEnabled) {
        this.treeResolutionEnabled = treeResolutionEnabled;
    }

    public boolean isBulkIndexTreeResolutionEnabled() {
        return bulkIndexTreeResolutionEnabled;
    }

    public void setBulkIndexTreeResolutionEnabled(boolean bulkIndexTreeResolutionEnabled) {
        this.bulkIndexTreeResolutionEnabled = bulkIndexTreeResolutionEnabled;
    }

    public int getTreeTimeoutSeconds() {
        return treeTimeoutSeconds;
    }

    public void setTreeTimeoutSeconds(int treeTimeoutSeconds) {
        this.treeTimeoutSeconds = treeTimeoutSeconds;
    }

    public int getMaxModulesPerIndex() {
        return maxModulesPerIndex;
    }

    public void setMaxModulesPerIndex(int maxModulesPerIndex) {
        this.maxModulesPerIndex = maxModulesPerIndex;
    }

    public int getTreeParallelism() {
        return treeParallelism;
    }

    public void setTreeParallelism(int treeParallelism) {
        this.treeParallelism = treeParallelism;
    }
}
