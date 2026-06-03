package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DualWriteService {

    private static final Logger log = LoggerFactory.getLogger(DualWriteService.class);

    private final JdbcClient db;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;

    public DualWriteService(JdbcClient db, MongoTemplate mongo, ObjectMapper mapper) {
        this.db = db;
        this.mongo = mongo;
        this.mapper = mapper;
    }

    @Transactional
    public void write(FactBatch batch, List<ParsedModel> models) {
        writeSymbolFacts(batch);
        writeOutboundCallFacts(batch);
        writePeripheralFacts(batch);
        writeUnsupportedConstructFacts(batch);
        writeParsedModels(batch, models);
    }

    private void writeSymbolFacts(FactBatch batch) {
        String sql = """
                INSERT INTO symbol_facts
                  (org_id, repo, service_id, commit_sha, file_path, symbol_fqn,
                   symbol_kind, snapshot_type, attributes, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :filePath, :symbolFqn,
                        :symbolKind, :snapshotType, :attributes::jsonb, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.SymbolFact f : batch.symbolFacts()) {
            db.sql(sql)
                    .param("orgId",          batch.orgId())
                    .param("repo",           batch.repo())
                    .param("serviceId",      batch.serviceId())
                    .param("commitSha",      batch.commitSha())
                    .param("filePath",       f.filePath())
                    .param("symbolFqn",      f.symbolFqn())
                    .param("symbolKind",     f.symbolKind())
                    .param("snapshotType",   batch.snapshotType())
                    .param("attributes",     f.attributes())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence",     f.confidence())
                    .update();
        }
    }

    private void writeOutboundCallFacts(FactBatch batch) {
        String sql = """
                INSERT INTO outbound_call_facts
                  (org_id, repo, service_id, commit_sha, source_symbol,
                   http_method, path, snapshot_type, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :sourceSymbol,
                        :httpMethod, :path, :snapshotType, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.OutboundCallFact f : batch.outboundCallFacts()) {
            db.sql(sql)
                    .param("orgId",          batch.orgId())
                    .param("repo",           batch.repo())
                    .param("serviceId",      batch.serviceId())
                    .param("commitSha",      batch.commitSha())
                    .param("sourceSymbol",   f.sourceSymbol())
                    .param("httpMethod",     f.httpMethod())
                    .param("path",           f.path())
                    .param("snapshotType",   batch.snapshotType())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence",     f.confidence())
                    .update();
        }
    }

    private void writePeripheralFacts(FactBatch batch) {
        String sql = """
                INSERT INTO peripheral_facts
                  (org_id, service_id, commit_sha, peripheral_type, detection_tier,
                   detection_signals, prerequisite_text, reason_code)
                VALUES (:orgId, :serviceId, :commitSha, :type, :tier,
                        :signals::jsonb, :text, :reasonCode)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.PeripheralFact f : batch.peripheralFacts()) {
            db.sql(sql)
                    .param("orgId",       batch.orgId())
                    .param("serviceId",   batch.serviceId())
                    .param("commitSha",   batch.commitSha())
                    .param("type",        f.peripheralType())
                    .param("tier",        f.detectionTier())
                    .param("signals",     "[\"" + f.detectionSignals() + "\"]")
                    .param("text",        f.prerequisiteText())
                    .param("reasonCode",  f.reasonCode())
                    .update();
        }
    }

    private void writeUnsupportedConstructFacts(FactBatch batch) {
        String sql = """
                INSERT INTO unsupported_construct_facts
                  (org_id, service_id, commit_sha, file_path, reason_code, detail)
                VALUES (:orgId, :serviceId, :commitSha, :filePath, :reasonCode, :detail)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.UnsupportedConstructFact f : batch.unsupportedConstructFacts()) {
            db.sql(sql)
                    .param("orgId",      batch.orgId())
                    .param("serviceId",  batch.serviceId())
                    .param("commitSha",  batch.commitSha())
                    .param("filePath",   f.filePath())
                    .param("reasonCode", f.reasonCode())
                    .param("detail",     f.detail())
                    .update();
        }
    }

    private void writeParsedModels(FactBatch batch, List<ParsedModel> models) {
        if (models.isEmpty()) return;
        try {
            Document doc = new Document();
            doc.put("_id", batch.orgId() + "/" + batch.repo() + "/" +
                           batch.serviceId() + "/" + batch.commitSha());
            doc.put("orgId",       batch.orgId());
            doc.put("repo",        batch.repo());
            doc.put("serviceId",   batch.serviceId());
            doc.put("commitSha",   batch.commitSha());
            doc.put("indexedAt",   Instant.now().toString());
            doc.put("models",      mapper.writeValueAsString(models));

            mongo.getCollection("parsed_models")
                 .replaceOne(new Document("_id", doc.get("_id")), doc,
                         new com.mongodb.client.model.ReplaceOptions().upsert(true));
        } catch (Exception ex) {
            log.error("MongoDB write failed for {}/{}: {}",
                    batch.serviceId(), batch.commitSha(), ex.getMessage());
            throw new RuntimeException("MongoDB write failed", ex);
        }
    }
}
