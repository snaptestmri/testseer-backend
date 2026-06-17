package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicGlobMatcherTest {

    @Test
    void matches_exactTopic() {
        assertThat(TopicGlobMatcher.matches("PDN_T.FOO", "PDN_T.FOO")).isTrue();
        assertThat(TopicGlobMatcher.matches("PDN_T.FOO", "PDN_T.BAR")).isFalse();
    }

    @Test
    void matches_wildcardSuffix() {
        assertThat(TopicGlobMatcher.matches("*.ASTRA", "PDN_T.ACTIVATE_OFFER.ASTRA")).isTrue();
        assertThat(TopicGlobMatcher.matches("*.ASTRA", "PDN_T.OTHER")).isFalse();
    }

    @Test
    void matches_partnerAdapterNotificationSuffix() {
        assertThat(TopicGlobMatcher.matches("*.PARTNER_ADAPTER_NOTIFICATION",
                "T.PARTNER_ADAPTER_NOTIFICATION")).isTrue();
        assertThat(TopicGlobMatcher.matches("*.PARTNER_ADAPTER_NOTIFICATION",
                "DEV_T.PARTNER_ADAPTER_NOTIFICATION")).isTrue();
        assertThat(TopicGlobMatcher.matches("*.UMO_EVENT", "T.UMO_EVENT")).isTrue();
    }

    @Test
    void matches_nullSafe() {
        assertThat(TopicGlobMatcher.matches(null, "PDN_T.FOO")).isFalse();
        assertThat(TopicGlobMatcher.matches("*.ASTRA", null)).isFalse();
    }
}
