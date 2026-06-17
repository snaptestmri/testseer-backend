package io.testseer.backend.query;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogQueryServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired CatalogQueryService catalogQueryService;
    @Autowired ServiceRegistryService serviceRegistry;
    @Autowired JdbcClient db;

    String serviceId;

    @BeforeEach
    void setup() {
        IntegrationTestDb.clearCoreFacts(db);
        serviceId = serviceRegistry.register(new RegistrationRequest(
                "quotient", "optimus-platform-framework", "platform-data", "MAVEN", "library", null, null, null
        )).serviceId();

        for (int i = 0; i < 5; i++) {
            db.sql("""
                    INSERT INTO data_object_facts
                      (org_id, repo, service_id, commit_sha, snapshot_type, entity_fqn, store_type,
                       physical_name, evidence_source, confidence)
                    VALUES ('quotient', 'optimus-platform-framework', :svcId, 'abc', 'LOCAL',
                            :entityFqn, 'MARIADB', :physicalName, 'ENTITY_ANNOTATION', 0.9)
                    """)
                    .param("svcId", serviceId)
                    .param("entityFqn", "com.example.Entity" + i)
                    .param("physicalName", "Table" + i)
                    .update();
        }
    }

    @Test
    void queryDataObjects_returnsPagedSliceWithTotal() {
        PageResult<CatalogQueryService.DataObjectView> page =
                catalogQueryService.queryDataObjects(serviceId, null, null, 2, 1);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).hasSize(2);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items().get(0).entityFqn()).isEqualTo("com.example.Entity1");
    }

    @Test
    void queryDataObjects_lastPageHasNoMore() {
        PageResult<CatalogQueryService.DataObjectView> page =
                catalogQueryService.queryDataObjects(serviceId, null, null, 10, 0);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).hasSize(5);
        assertThat(page.hasMore()).isFalse();
    }
}
