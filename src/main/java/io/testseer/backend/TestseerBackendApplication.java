package io.testseer.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        io.testseer.backend.config.ObservabilityProperties.class,
        io.testseer.backend.config.ContractProperties.class})
public class TestseerBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestseerBackendApplication.class, args);
    }
}
