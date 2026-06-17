package io.testseer.backend.ingestion.messaging;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvLaneResolverTest {

    @Test
    void resolve_pdnProfile() {
        var profile = EnvLaneResolver.resolve("offer-events/src/main/resources/application-pdn.yaml");
        assertThat(profile.envLane()).isEqualTo("pdn");
        assertThat(profile.envProfile()).isEqualTo("pdn");
    }

    @Test
    void resolve_moduleNameFromPath() {
        assertThat(EnvLaneResolver.resolveModuleName(
                "optimus-offer-services-suite/offer-events-consumer/src/main/resources/application-pdn.yaml"))
                .isEqualTo("offer-events-consumer");
    }

    @Test
    void resolve_moduleNameFromJavaSourcePath() {
        assertThat(EnvLaneResolver.resolveModuleName(
                "partner-adapter-consumer/src/main/java/com/quotient/platform/partneradapter/consumer/PartnerAdapterConsumer.java"))
                .isEqualTo("partner-adapter-consumer");
    }

    @Test
    void resolve_moduleNameFromKotlinSourcePath() {
        assertThat(EnvLaneResolver.resolveModuleName(
                "some-module/src/main/kotlin/com/example/Foo.kt"))
                .isEqualTo("some-module");
    }

    @Test
    void resolveWorkloadName_appendsNsSuffix() {
        assertThat(EnvLaneResolver.resolveWorkloadName("offer-ingestion")).isEqualTo("offer-ingestion-ns");
        assertThat(EnvLaneResolver.resolveWorkloadName("offer-ingestion-ns")).isEqualTo("offer-ingestion-ns");
    }

    @Test
    void resolve_kubernetesManifestDirectoryLane() {
        var profile = EnvLaneResolver.resolve(
                "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml");
        assertThat(profile.envLane()).isEqualTo("dev");
    }

    @Test
    void resolve_springProfilesActiveFromFlatMap() {
        var profile = EnvLaneResolver.resolve(
                "some/config-map.yaml#application.yaml",
                Map.of("spring.profiles.active", "dev"));
        assertThat(profile.envLane()).isEqualTo("dev");
    }
}
