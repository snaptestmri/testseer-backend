package io.testseer.backend.registry;

import io.testseer.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ServiceRegistryRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM service_registry").update();
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
}
