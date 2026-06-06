package io.testseer.backend.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "testseer.anthropic.enabled", havingValue = "true")
public class ServiceDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(ServiceDescriptionService.class);

    private final JdbcClient db;
    private final AnthropicClient anthropic;

    public ServiceDescriptionService(
            JdbcClient db,
            @Value("${testseer.anthropic.api-key:}") String apiKey) {
        this.db        = db;
        this.anthropic = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    public String generateAndStore(String serviceId) {
        String description = generate(serviceId);
        db.sql("""
                UPDATE service_registry
                SET metadata = COALESCE(metadata, '{}')::jsonb
                           || jsonb_build_object('description', :desc,
                                                  'descriptionGeneratedAt', now()::text)
                WHERE service_id = :id
                """)
                .param("desc", description)
                .param("id",   serviceId)
                .update();
        log.info("Stored description for service {}", serviceId);
        return description;
    }

    public String getStored(String serviceId) {
        return db.sql("""
                SELECT metadata->>'description'
                FROM service_registry
                WHERE service_id = :id
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String generate(String serviceId) {
        String latestSha = db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (latestSha == null) return "Service has not been indexed yet.";

        String classSummary = db.sql("""
                SELECT symbol_fqn, attributes->>'javadoc' as javadoc
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'CLASS'
                ORDER BY symbol_fqn LIMIT 20
                """)
                .param("id", serviceId).param("sha", latestSha)
                .query((rs, row) -> rs.getString("symbol_fqn") +
                        (rs.getString("javadoc") != null ? ": " + rs.getString("javadoc") : ""))
                .list().stream().collect(Collectors.joining("\n"));

        String methodSummary = db.sql("""
                SELECT symbol_fqn,
                       attributes->>'javadoc' as javadoc,
                       attributes->>'thrownExceptions' as exceptions
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'METHOD'
                  AND (attributes->>'javadoc' IS NOT NULL
                       OR attributes->>'thrownExceptions' != '[]')
                ORDER BY symbol_fqn LIMIT 30
                """)
                .param("id", serviceId).param("sha", latestSha)
                .query((rs, row) -> {
                    String fqn = rs.getString("symbol_fqn");
                    String doc = rs.getString("javadoc");
                    String exc = rs.getString("exceptions");
                    return fqn
                            + (doc != null && !doc.isBlank() ? " — " + doc : "")
                            + (exc != null && !"[]".equals(exc) ? " [throws: " + exc + "]" : "");
                })
                .list().stream().collect(Collectors.joining("\n"));

        String enumSummary = db.sql("""
                SELECT symbol_fqn,
                       attributes->>'enumValues' as values,
                       attributes->>'javadoc' as javadoc
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'ENUM'
                ORDER BY symbol_fqn LIMIT 10
                """)
                .param("id", serviceId).param("sha", latestSha)
                .query((rs, row) -> rs.getString("symbol_fqn")
                        + " values: " + rs.getString("values")
                        + (rs.getString("javadoc") != null ? " — " + rs.getString("javadoc") : ""))
                .list().stream().collect(Collectors.joining("\n"));

        String prompt = """
                You are analysing a Java microservice to produce a concise business description.

                Classes and their documentation:
                %s

                Key methods with business rules:
                %s

                State machines (enums):
                %s

                In 3-5 sentences, describe:
                1. What business capability this service provides
                2. The main operations it supports
                3. Notable business rules inferred from exception types and Javadoc

                Write in plain English for a technical product manager. No bullet points.
                """.formatted(
                classSummary.isBlank()  ? "(none indexed)" : classSummary,
                methodSummary.isBlank() ? "(none indexed)" : methodSummary,
                enumSummary.isBlank()   ? "(none indexed)" : enumSummary
        );

        try {
            var message = anthropic.messages().create(
                    MessageCreateParams.builder()
                            .model(Model.CLAUDE_3_5_HAIKU_LATEST)
                            .maxTokens(400)
                            .addUserMessage(prompt)
                            .build()
            );
            return message.content().stream()
                    .filter(b -> b.isText())
                    .map(b -> b.asText().text())
                    .findFirst()
                    .orElse("Could not generate description.");
        } catch (Exception ex) {
            log.error("Failed to generate description for {}: {}", serviceId, ex.getMessage());
            throw new RuntimeException("Description generation failed for " + serviceId, ex);
        }
    }
}
