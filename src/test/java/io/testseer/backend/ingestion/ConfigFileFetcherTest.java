package io.testseer.backend.ingestion;

import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileFetcherTest {

    private final ConfigFileFetcher fetcher = new ConfigFileFetcher();

    @Test
    void fetchFromDirectory_includesYamlAndProto(@TempDir Path root) throws IOException {
        Path module = root.resolve("offer-events-consumer/src/main/resources");
        Files.createDirectories(module);
        Files.writeString(module.resolve("application-pdn.yaml"), "pubsub:\n  enabled: true\n");
        Files.writeString(module.resolve("OfferUpdate.proto"), "syntax = \"proto3\";\nmessage Offer {}\n");

        List<YamlPubSubExtractor.ConfigFile> files = fetcher.fetchFromDirectory(root.toString());

        assertThat(files).anyMatch(f -> f.path().endsWith("application-pdn.yaml"));
        assertThat(files).anyMatch(f -> f.path().endsWith("OfferUpdate.proto"));
    }

    @Test
    void fetchFromRoots_scansConfiguredSubRootsOnly(@TempDir Path root) throws IOException {
        Path protoRoot = root.resolve("platform-msg-events/src/main/resources/protobuf/events");
        Files.createDirectories(protoRoot);
        Files.writeString(protoRoot.resolve("offer.proto"), "syntax = \"proto3\";\nmessage Offer {}\n");
        Path ignored = root.resolve("other");
        Files.createDirectories(ignored);
        Files.writeString(ignored.resolve("ignored.proto"), "syntax = \"proto3\";\nmessage Ignored {}\n");

        List<YamlPubSubExtractor.ConfigFile> files = fetcher.fetchFromRoots(
                root.toString(),
                List.of("platform-msg-events/src/main/resources/protobuf"));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("offer.proto");
    }

    @Test
    void filterConfigPaths_skipsNonConfigFiles() {
        List<String> paths = List.of(
                "src/main/java/Foo.java",
                "src/main/resources/application-pdn.yaml",
                "src/main/resources/events/Offer.proto",
                "README.md"
        );

        List<String> filtered = fetcher.filterConfigPaths(paths);

        assertThat(filtered).containsExactly(
                "src/main/resources/application-pdn.yaml",
                "src/main/resources/events/Offer.proto");
    }
}
