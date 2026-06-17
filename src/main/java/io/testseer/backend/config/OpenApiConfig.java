package io.testseer.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI testseerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TestSeer API")
                        .description("""
                                TestSeer analyses Java service codebases to produce a structured \
                                knowledge graph of classes, endpoints, and outbound HTTP calls. \
                                Use the Query API to ask questions about your services — reachability, \
                                impact analysis, shared type consumers — and always get freshness \
                                metadata alongside results.""")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("TestSeer Team")
                                .email("team@testseer.io"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")
                ));
    }

    /** Stable tag ordering so OpenApiExportTest and OpenApiGovernanceTest agree across JVM runs. */
    @Bean
    public OpenApiCustomizer stableOpenApiTagOrdering() {
        return openApi -> {
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER));
            }
        };
    }
}
