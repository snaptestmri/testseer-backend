package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.FactExtractor;
import io.testseer.backend.ingestion.JavaParserService;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BL-065 user-profile pilot graph fixture — Lombok {@code @AllArgsConstructor} controller chain.
 */
public final class UserProfileGraphFixture {

    public static final String ORG = "quotient";
    public static final String REPO = "platform-user-profile";
    public static final String SERVICE_NAME = "user-profile-service";

    public static final String CONTROLLER =
            "com.quotient.platform.userprofile.web.api.UserHistoryApiController";
    public static final String SHOPPING_SERVICE =
            "com.quotient.platform.userprofile.service.ShoppingHistoryServiceImpl";
    public static final String SHOPPING_HELPER =
            "com.quotient.platform.userprofile.helper.ShoppingHistoryHelper";

    private static final String CONTROLLER_SOURCE = """
            package com.quotient.platform.userprofile.web.api;
            import lombok.AllArgsConstructor;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.web.bind.annotation.RestController;
            import com.quotient.platform.userprofile.service.ShoppingHistoryServiceImpl;
            import com.quotient.platform.userprofile.helper.ShoppingHistoryHelper;

            @RestController
            @AllArgsConstructor(onConstructor_ = {@Autowired})
            public class UserHistoryApiController {
                private final ShoppingHistoryServiceImpl shoppingHistoryServiceImpl;
                private ShoppingHistoryHelper shoppingHistoryHelper;

                public void fetchUserProfileShoppingHistory() {
                    shoppingHistoryHelper.validateAndGetShoppingHistoryContext();
                    shoppingHistoryServiceImpl.getShoppingHistoryForUser();
                }
            }
            """;

    private static final String SERVICE_SOURCE = """
            package com.quotient.platform.userprofile.service;
            import org.springframework.stereotype.Service;

            @Service
            public class ShoppingHistoryServiceImpl {
                public void getShoppingHistoryForUser() {}
            }
            """;

    private static final String HELPER_SOURCE = """
            package com.quotient.platform.userprofile.helper;
            import org.springframework.stereotype.Component;

            @Component
            public class ShoppingHistoryHelper {
                public void validateAndGetShoppingHistoryContext() {}
            }
            """;

    private UserProfileGraphFixture() {}

    public static ServiceEntry load(JavaParserService parser,
                                    GraphFactProjector graphProjector,
                                    FactExtractor factExtractor,
                                    ServiceRegistryService svcRegistry,
                                    JdbcClient db) {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM symbol_facts").update();
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();

        ServiceEntry svc = svcRegistry.register(new RegistrationRequest(
                ORG, REPO, SERVICE_NAME, "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        String serviceId = svc.serviceId();

        db.sql("""
                INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha,
                    job_type, status, attempt, enqueued_at, completed_at)
                VALUES ('up-gap-05-fixture', :orgId, :svcId, 'fixture', 'LOCAL', 'COMPLETE', 1, now(), now())
                """).param("orgId", ORG).param("svcId", serviceId).update();

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(CONTROLLER, CONTROLLER_SOURCE);
        sources.put(SHOPPING_SERVICE, SERVICE_SOURCE);
        sources.put(SHOPPING_HELPER, HELPER_SOURCE);

        List<ParsedModel> models = new ArrayList<>();
        Map<String, String> sourceByFqn = new LinkedHashMap<>();
        for (var entry : sources.entrySet()) {
            String path = "src/main/java/" + entry.getKey().replace('.', '/') + ".java";
            ParsedModel model = parser.parse(path, entry.getValue());
            models.add(model);
            sourceByFqn.put(entry.getKey(), entry.getValue());
        }

        List<FactBatch.SymbolFact> facts = new ArrayList<>();
        for (ParsedModel model : models) {
            facts.addAll(factExtractor.extractSymbolFacts(model));
        }

        FactBatch batch = FactBatch.core(
                "up-gap-05-fixture", ORG, REPO, serviceId, "fixture", "LOCAL",
                facts, List.of(), List.of(), List.of());

        graphProjector.project(batch, models, sourceByFqn);
        return svc;
    }
}
