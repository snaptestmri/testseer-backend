package io.testseer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "testseer.contracts")
public record ContractProperties(List<String> testSuiteRepos) {

    public boolean isTestSuiteRepo(String repo) {
        if (repo == null || testSuiteRepos == null || testSuiteRepos.isEmpty()) {
            return false;
        }
        return testSuiteRepos.stream().anyMatch(repo::contains);
    }
}
