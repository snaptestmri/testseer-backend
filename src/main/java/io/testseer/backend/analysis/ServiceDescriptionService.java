package io.testseer.backend.analysis;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class ServiceDescriptionService {

    public record StoredDescription(String description, Instant generatedAt, String model) {}

    private final JdbcClient db;

    public ServiceDescriptionService(JdbcClient db) {
        this.db = db;
    }

    public Optional<StoredDescription> getStoredDetails(String serviceId) {
        return db.sql("""
                SELECT metadata->>'description' AS description,
                       metadata->>'descriptionGeneratedAt' AS generated_at,
                       metadata->>'descriptionModel' AS model
                FROM service_registry
                WHERE service_id = :id
                """)
                .param("id", serviceId)
                .query((rs, row) -> {
                    String description = rs.getString("description");
                    if (description == null || description.isBlank()) {
                        return null;
                    }
                    String generatedAtText = rs.getString("generated_at");
                    Instant generatedAt = generatedAtText != null && !generatedAtText.isBlank()
                            ? Instant.parse(generatedAtText)
                            : null;
                    String model = rs.getString("model");
                    return new StoredDescription(description, generatedAt, model);
                })
                .optional()
                .flatMap(Optional::ofNullable);
    }
}
