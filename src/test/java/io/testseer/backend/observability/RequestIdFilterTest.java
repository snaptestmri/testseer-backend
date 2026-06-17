package io.testseer.backend.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class RequestIdFilterTest extends io.testseer.backend.AbstractIntegrationTest {

    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;

    @Test
    void echoesProvidedRequestId() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "corr-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-123"));
    }

    @Test
    void generatesRequestIdWhenMissing() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }
}
