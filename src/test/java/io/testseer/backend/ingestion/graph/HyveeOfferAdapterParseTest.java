package io.testseer.backend.ingestion.graph;

import io.testseer.backend.ingestion.JavaParserService;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HyveeOfferAdapterParseTest {

    @Test
    void hyveeOfferAdapter_parsesWithoutError() throws Exception {
        Path path = Path.of(System.getProperty("user.home"),
                "Documents/GitHub/riq-partner-adapter-suite/partner-adapter-lib/src/main/java",
                "com/quotient/platform/partneradapter/lib/adapter/HyveeOfferAdapter.java");
        if (!Files.exists(path)) {
            return;
        }
        String content = Files.readString(path);
        ParsedModel model = new JavaParserService(null).parse(path.toString(), content);
        assertThat(model.parseError())
                .as("parseErrorDetail: %s", model.parseErrorDetail())
                .isFalse();
        assertThat(model.classFqn())
                .isEqualTo("com.quotient.platform.partneradapter.lib.adapter.HyveeOfferAdapter");
    }
}
