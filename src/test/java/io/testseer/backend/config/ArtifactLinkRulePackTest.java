package io.testseer.backend.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactLinkRulePackTest {

    @Test
    void catalogLibraryFor_matchesGroupAndArtifact() {
        ArtifactLinkRulePack pack = new ArtifactLinkRulePack(List.of(
                new ArtifactLinkRulePack.ArtifactAliasRule(
                        "com.quotient", "platform-evaluation-lib", "evaluation-lib")));

        assertThat(pack.catalogLibraryFor("com.quotient", "platform-evaluation-lib"))
                .contains("evaluation-lib");
        assertThat(pack.catalogLibraryFor("com.other", "platform-evaluation-lib"))
                .isEmpty();
    }

    @Test
    void catalogLibraryFor_allowsWildcardGroup() {
        ArtifactLinkRulePack pack = new ArtifactLinkRulePack(List.of(
                new ArtifactLinkRulePack.ArtifactAliasRule(null, "platform-evaluation-lib", "evaluation-lib")));

        assertThat(pack.catalogLibraryFor("com.quotient", "platform-evaluation-lib"))
                .contains("evaluation-lib");
    }
}
