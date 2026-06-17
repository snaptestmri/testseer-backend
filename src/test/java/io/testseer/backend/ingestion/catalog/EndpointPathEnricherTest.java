package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.triggers.InboundRestTriggerExtractor;
import io.testseer.backend.config.TriggerRulePack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BL-062 / UP-GAP-01 — constant URL resolution on {@code @RequestMapping}.
 */
class EndpointPathEnricherTest {

  private final EndpointPathEnricher enricher = new EndpointPathEnricher();
  private final InboundRestTriggerExtractor triggerExtractor = new InboundRestTriggerExtractor();

  @Test
  void enrich_resolvesUserProfileOfferTransactionHistoryPath() {
    String constants = """
        package com.quotient.platform.userprofile.util;
        public class Constants {
            public static final String USER_OFFER_TRANSACTION_API_URL = "/offer/transaction/history";
        }
        """;
    String controller = """
        package com.quotient.platform.userprofile.web.api;
        import org.springframework.http.MediaType;
        import org.springframework.web.bind.annotation.*;
        import static com.quotient.platform.userprofile.util.Constants.*;
        @RestController
        public class UserHistoryApiController {
            @RequestMapping(value = USER_OFFER_TRANSACTION_API_URL,
                    produces = { MediaType.APPLICATION_JSON_VALUE },
                    consumes = { MediaType.APPLICATION_JSON_VALUE },
                    method = RequestMethod.POST)
            public void getUserOfferTransactionHistory() {}
        }
        """;

    ParsedModel rawModel = stubModel("UserHistoryApiController.java",
        "com.quotient.platform.userprofile.web.api.UserHistoryApiController",
        List.of(new ParsedModel.EndpointDef(
            "POST", "/USER_OFFER_TRANSACTION_API_URL", "getUserOfferTransactionHistory")));

    List<MessagingFactOrchestrator.SourceFile> sources = List.of(
        new MessagingFactOrchestrator.SourceFile("Constants.java", constants,
            ParsedModel.of("Constants.java", "com.quotient.platform.userprofile.util.Constants",
                List.of(), List.of(), List.of(), List.of(), List.of(), false, null, null, List.of(), List.of())),
        new MessagingFactOrchestrator.SourceFile("UserHistoryApiController.java", controller, rawModel));

    List<MessagingFactOrchestrator.SourceFile> enriched = enricher.enrich(sources);
    ParsedModel model = enriched.get(1).parsedModel();

    assertThat(model.endpoints()).singleElement().satisfies(ep -> {
      assertThat(ep.path()).isEqualTo("/offer/transaction/history");
      assertThat(ep.pathResolution()).isEqualTo("FIELD");
      assertThat(ep.pathSourceFieldFqn()).isEqualTo(
          "com.quotient.platform.userprofile.util.Constants#USER_OFFER_TRANSACTION_API_URL");
    });

    var triggers = triggerExtractor.extract(
        enriched.stream().map(MessagingFactOrchestrator.SourceFile::parsedModel).toList(),
        TriggerRulePack.empty(),
        "dev");

    assertThat(triggers).anySatisfy(t -> {
      assertThat(t.pathPattern()).isEqualTo("/offer/transaction/history");
      assertThat(t.httpMethod()).isEqualTo("POST");
      assertThat(t.attributes()).contains("pathSourceFieldFqn");
    });
    assertThat(triggers).noneMatch(t -> t.pathPattern().contains("USER_OFFER_TRANSACTION"));
  }

  @Test
  void enrich_literalPathsUnchanged() {
    String controller = """
        package com.example;
        import org.springframework.web.bind.annotation.*;
        @RestController
        public class HistoryController {
            @PostMapping("/shopping/history")
            public void getShoppingHistory() {}
        }
        """;
    ParsedModel raw = stubModel("HistoryController.java", "com.example.HistoryController",
        List.of(new ParsedModel.EndpointDef("POST", "/shopping/history", "getShoppingHistory")));
    List<MessagingFactOrchestrator.SourceFile> enriched = enricher.enrich(List.of(
        new MessagingFactOrchestrator.SourceFile("HistoryController.java", controller, raw)));

    assertThat(enriched.get(0).parsedModel().endpoints()).singleElement()
        .extracting(ParsedModel.EndpointDef::path)
        .isEqualTo("/shopping/history");
  }

  private static ParsedModel stubModel(String path, String fqn, List<ParsedModel.EndpointDef> endpoints) {
    return ParsedModel.of(path, fqn, List.of("RestController"), List.of(), List.of(),
        endpoints, List.of(), false, null, null, List.of(), List.of());
  }
}
