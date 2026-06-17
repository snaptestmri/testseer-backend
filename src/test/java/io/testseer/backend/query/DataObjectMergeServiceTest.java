package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataObjectMergeServiceTest {

    @Mock DataObjectValidationService validationService;

    DataObjectMergeService mergeService;

    private static final String ENTITY_FQN =
            "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity";

    @BeforeEach
    void setUp() {
        MessagingRulePackLoader loader = new MessagingRulePackLoader(
                new FileSystemResource("../config/rule-packs/quotient-messaging.yml"));
        mergeService = new DataObjectMergeService(loader, validationService);
    }

    @Test
    void merge_appliesRulePackPollHint() {
        when(validationService.validate(anyString(), anyString(), anyString(), any()))
                .thenReturn("DDL_CONFIRMED");

        MessagingFlowService.DataAccessView raw = baseView(
                "PartnerOfferCallRecorder", "saveToDb", ENTITY_FQN, "MARIADB", 0.93);

        MessagingFlowService.DataAccessView merged = mergeService.merge("acme", raw);

        assertThat(merged.pollHint()).contains("PartnerOfferCallRecorder");
        assertThat(merged.validationKind()).isEqualTo("DDL_CONFIRMED");
        assertThat(merged.confidence()).isEqualTo(0.98, within(0.001));
        assertThat(merged.evidenceSource()).contains("RULE_PACK");
        assertThat(merged.flowSteps()).contains("HYVEE_ADAPTER");
    }

    @Test
    void merge_marksInferredNotInDdlWhenNoSchemaMatch() {
        when(validationService.validate(eq("acme"), eq("CASSANDRA"), eq("UserOfferActivated"), isNull()))
                .thenReturn("INFERRED_NOT_IN_DDL");

        MessagingFlowService.DataAccessView raw = new MessagingFlowService.DataAccessView(
                "com.example.ActivationWriter",
                "onActivated",
                "WRITE",
                "UserOfferActivated",
                "save",
                null,
                "MONGO_ACCESS",
                0.78,
                "CASSANDRA",
                null,
                null,
                null,
                null,
                null,
                null,
                "UserOfferActivated",
                null,
                null,
                null,
                List.of()
        );

        MessagingFlowService.DataAccessView merged = mergeService.merge("acme", raw);

        assertThat(merged.validationKind()).isEqualTo("INFERRED_NOT_IN_DDL");
        assertThat(merged.pollHint()).contains("PartnerId");
        assertThat(merged.flowSteps()).contains("FREEDOM_UMO");
    }

    @Test
    void merge_migratesLegacyDbTableHintWhenNoExplicitDataObject() {
        MessagingRulePack pack = new MessagingRulePack(
                List.of(), List.of(),
                Map.of("notification_tracking",
                        new MessagingRulePack.DbTableHintRule(
                                "SELECT * FROM notification_tracking WHERE offer_id = ?")),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                MessagingRulePack.CrossRepoTraceRule.empty());
        Map<String, MessagingRulePack.DataObjectRule> migrated = new java.util.LinkedHashMap<>();
        migrated.put("NotificationTracking", new MessagingRulePack.DataObjectRule(
                null, "NotificationTracking", null, null, null, null, null,
                "SELECT * FROM notification_tracking WHERE offer_id = ?", null, null, null));
        MessagingRulePack withMigration = new MessagingRulePack(
                pack.topicFlowSteps(), pack.classFlowSteps(), pack.dbTableHints(),
                pack.codeGateRules(), pack.classFlowStepRules(), pack.partnerEndpoints(),
                pack.externalEndpointHints(), migrated, Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                pack.crossRepoTrace());

        MessagingRulePackLoader loader = org.mockito.Mockito.mock(MessagingRulePackLoader.class);
        when(loader.getRulePack()).thenReturn(withMigration);
        mergeService = new DataObjectMergeService(loader, validationService);
        when(validationService.validate(anyString(), anyString(), anyString(), any()))
                .thenReturn(null);

        MessagingFlowService.DataAccessView raw = baseView(
                "NotificationTracking", "find", null, "MARIADB", 0.8);

        MessagingFlowService.DataAccessView merged = mergeService.merge("acme", raw);

        assertThat(merged.pollHint()).contains("notification_tracking");
    }

    private static MessagingFlowService.DataAccessView baseView(
            String table, String daoMethod, String entityFqn, String storeType, double confidence) {
        return new MessagingFlowService.DataAccessView(
                "com.example.Handler",
                "handle",
                "WRITE",
                table,
                daoMethod,
                "[\"offerId\"]",
                "HANDLER_LINKER+CATALOG",
                confidence,
                storeType,
                entityFqn,
                null,
                "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao",
                "DAO",
                "coupons_nextgen",
                null,
                table,
                null,
                null,
                null,
                List.of()
        );
    }
}
