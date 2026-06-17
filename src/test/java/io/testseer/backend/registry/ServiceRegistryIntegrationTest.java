package io.testseer.backend.registry;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ServiceRegistryRepository repository;

    @Autowired
    ServiceRegistryService service;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void cleanup() {
        IntegrationTestDb.clearCoreFacts(jdbcClient);
    }

    @Test
    void saveAndFindById() {
        var entry = new ServiceEntry(
                "svc-001", "acme", "order-service", "orders",
                "service", "MAVEN",
                List.of("src/main/java"),
                List.of("src/test/java"),
                "platform", true, null, null
        );

        repository.save(entry);

        Optional<ServiceEntry> found = repository.findById("svc-001");

        assertThat(found).isPresent();
        assertThat(found.get().orgId()).isEqualTo("acme");
        assertThat(found.get().serviceName()).isEqualTo("orders");
        assertThat(found.get().enabled()).isTrue();
    }

    @Test
    void registerService_returnsServiceEntry() {
        var req = new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), List.of("src/test/java"), "platform"
        );

        ServiceEntry result = service.register(req);

        assertThat(result.orgId()).isEqualTo("acme");
        assertThat(result.repo()).isEqualTo("order-service");
        assertThat(result.enabled()).isTrue();
        assertThat(result.serviceId()).isNotBlank();
    }

    @Test
    void register_throwsDuplicateException_whenSameOrgRepoService() {
        var req = new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                null, null, null, null
        );
        service.register(req);

        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateServiceException.class,
                () -> service.register(req)
        );
    }

    @Test
    void disable_setsEnabledFalse_withoutDeletingEntry() {
        var req = new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                null, null, null, null
        );
        ServiceEntry entry = service.register(req);

        service.disable(entry.serviceId());

        Optional<ServiceEntry> found = repository.findById(entry.serviceId());
        assertThat(found).isPresent();
        assertThat(found.get().enabled()).isFalse();
    }

    @Test
    void fullRegistrationRoundTrip() {
        var req = new RegistrationRequest(
                "acme", "inventory-service", "inventory", "GRADLE",
                "service", null, null, "supply-chain"
        );

        var created = restTemplate.postForEntity("/registry/services", req, ServiceEntry.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        String serviceId = created.getBody().serviceId();
        var fetched = restTemplate.getForEntity(
                "/registry/services/" + serviceId, ServiceEntry.class);

        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().orgId()).isEqualTo("acme");
        assertThat(fetched.getBody().enabled()).isTrue();
    }
}
