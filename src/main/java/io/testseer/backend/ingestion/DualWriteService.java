package io.testseer.backend.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.ReplaceOptions;
import io.testseer.backend.observability.IndexingPhaseTimer;
import org.bson.BsonMaximumSizeExceededException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DualWriteService {

    private static final Logger log = LoggerFactory.getLogger(DualWriteService.class);
    /** Leave headroom below MongoDB's 16MB BSON document cap for metadata fields. */
    static final int PARSED_MODELS_CHUNK_TARGET_CHARS = 12_000_000;

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
        IndexingPhaseTimer timer = new IndexingPhaseTimer();
        writeSymbolFacts(batch);
        timer.lap("symbolFacts");
        writeOutboundCallFacts(batch);
        timer.lap("outboundCallFacts");
        writePeripheralFacts(batch);
        timer.lap("peripheralFacts");
        writeUnsupportedConstructFacts(batch);
        timer.lap("unsupportedConstructFacts");
        writePubSubResourceFacts(batch);
        timer.lap("pubsubResourceFacts");
        writeMessageSchemaFacts(batch);
        timer.lap("messageSchemaFacts");
        writeDataAccessFacts(batch);
        timer.lap("dataAccessFacts");
        writeFlowGateFacts(batch);
        timer.lap("flowGateFacts");
        writeValidationHintFacts(batch);
        timer.lap("validationHintFacts");
        writeExternalEndpointFacts(batch);
        timer.lap("externalEndpointFacts");
        writeExternalCallSiteFacts(batch);
        timer.lap("externalCallSiteFacts");
        writeDataObjectFacts(batch);
        timer.lap("dataObjectFacts");
        writeAccessorMethodFacts(batch);
        timer.lap("accessorMethodFacts");
        writeSchemaObjectFacts(batch);
        timer.lap("schemaObjectFacts");
        writeEntryTriggerFacts(batch);
        timer.lap("entryTriggerFacts");
        writeConsistencyScenarioFacts(batch);
        timer.lap("consistencyScenarioFacts");
        writeContractOperationFacts(batch);
        timer.lap("contractOperationFacts");
        writeContractSchemaFacts(batch);
        timer.lap("contractSchemaFacts");
        writeTestHttpCallFacts(batch);
        timer.lap("testHttpCallFacts");
        writeAsyncRetryPathFacts(batch);
        timer.lap("asyncRetryPathFacts");
        writeMavenModuleFacts(batch);
        timer.lap("mavenModuleFacts");
        writeMavenDependencyFacts(batch);
        timer.lap("mavenDependencyFacts");
        writeParsedModels(batch, models);
        timer.lap("parsedModels");
        log.info("DualWrite timing serviceId={} commit={} totalMs={} {}",
                batch.serviceId(), batch.commitSha(), timer.elapsedMs(), timer.formatPhases());
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

    private void writePubSubResourceFacts(FactBatch batch) {
        if (batch.pubsubResourceFacts().isEmpty()) {
            return;
        }
        db.sql("""
                DELETE FROM pubsub_resource_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("serviceId", batch.serviceId())
                .param("commitSha", batch.commitSha())
                .update();

        String sql = """
                INSERT INTO pubsub_resource_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, resource_kind, short_id,
                   env_lane, env_profile, gcp_project, full_resource_id, role, spring_key, yaml_path,
                   module_name, linked_class_fqn, linked_method, workload_name, evidence_source,
                   confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :resourceKind, :shortId,
                        :envLane, :envProfile, :gcpProject, :fullResourceId, :role, :springKey, :yamlPath,
                        :moduleName, :linkedClassFqn, :linkedMethod, :workloadName, :evidenceSource,
                        :confidence, :attributes::jsonb)
                """;
        List<FactBatch.PubSubResourceFact> pubsubFacts = dedupePubSubResourceFacts(batch.pubsubResourceFacts());
        if (pubsubFacts.size() < batch.pubsubResourceFacts().size()) {
            log.warn("Dropped {} duplicate pubsub_resource_facts for service={} commit={}",
                    batch.pubsubResourceFacts().size() - pubsubFacts.size(),
                    batch.serviceId(), batch.commitSha());
        }
        for (FactBatch.PubSubResourceFact f : pubsubFacts) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("resourceKind", f.resourceKind())
                    .param("shortId", f.shortId())
                    .param("envLane", f.envLane())
                    .param("envProfile", f.envProfile())
                    .param("gcpProject", f.gcpProject())
                    .param("fullResourceId", f.fullResourceId())
                    .param("role", f.role())
                    .param("springKey", nullToEmpty(f.springKey()))
                    .param("yamlPath", f.yamlPath())
                    .param("moduleName", f.moduleName())
                    .param("linkedClassFqn", nullToEmpty(f.linkedClassFqn()))
                    .param("linkedMethod", f.linkedMethod())
                    .param("workloadName", f.workloadName())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeMessageSchemaFacts(FactBatch batch) {
        String sql = """
                INSERT INTO message_schema_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, envelope_type, payload_proto,
                   payload_fields, payload_enums, linked_class_fqn, linked_method, direction,
                   topic_short_id, unpack_expression, proto_file, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :envelopeType, :payloadProto,
                        :payloadFields::jsonb, :payloadEnums::jsonb, :linkedClassFqn, :linkedMethod, :direction,
                        :topicShortId, :unpackExpression, :protoFile, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.MessageSchemaFact f : batch.messageSchemaFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("envelopeType", f.envelopeType())
                    .param("payloadProto", f.payloadProto())
                    .param("payloadFields", f.payloadFields())
                    .param("payloadEnums", f.payloadEnums())
                    .param("linkedClassFqn", f.linkedClassFqn())
                    .param("linkedMethod", f.linkedMethod())
                    .param("direction", f.direction())
                    .param("topicShortId", f.topicShortId())
                    .param("unpackExpression", f.unpackExpression())
                    .param("protoFile", f.protoFile())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeDataAccessFacts(FactBatch batch) {
        String sql = """
                INSERT INTO data_access_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, handler_class_fqn, handler_method,
                   operation, store_type, table_or_entity, repository_fqn, dao_method, correlation_keys,
                   validation_hint, evidence_source, confidence,
                   entity_fqn, domain_fqn, accessor_fqn, accessor_kind, catalog_ref, secondary_stores)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :handlerClassFqn, :handlerMethod,
                        :operation, :storeType, :tableOrEntity, :repositoryFqn, :daoMethod, :correlationKeys::jsonb,
                        CAST(:validationHint AS jsonb), :evidenceSource, :confidence,
                        :entityFqn, :domainFqn, :accessorFqn, :accessorKind, :catalogRef, :secondaryStores::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.DataAccessFact f : batch.dataAccessFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("handlerClassFqn", f.handlerClassFqn())
                    .param("handlerMethod", f.handlerMethod())
                    .param("operation", f.operation())
                    .param("storeType", f.storeType())
                    .param("tableOrEntity", f.tableOrEntity())
                    .param("repositoryFqn", f.repositoryFqn())
                    .param("daoMethod", f.daoMethod())
                    .param("correlationKeys", f.correlationKeys() != null ? f.correlationKeys() : "[]")
                    .param("validationHint", f.validationHint())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("entityFqn", f.entityFqn())
                    .param("domainFqn", f.domainFqn())
                    .param("accessorFqn", f.accessorFqn())
                    .param("accessorKind", f.accessorKind())
                    .param("catalogRef", f.catalogRef())
                    .param("secondaryStores", f.secondaryStores() != null ? f.secondaryStores() : null)
                    .update();
        }
    }

    private void writeFlowGateFacts(FactBatch batch) {
        String sql = """
                INSERT INTO flow_gate_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, env_lane, guarded_symbol_fqn,
                   guarded_flow_step, guarded_edge_type, gate_kind, gate_key, required_value,
                   required_operator, effect_when_fail, skip_log_pattern, test_precondition,
                   evidence_source, yaml_path, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :envLane, :guardedSymbolFqn,
                        :guardedFlowStep, :guardedEdgeType, :gateKind, :gateKey, :requiredValue,
                        :requiredOperator, :effectWhenFail, :skipLogPattern, :testPrecondition,
                        :evidenceSource, :yamlPath, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.FlowGateFact f : batch.flowGateFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("envLane", f.envLane())
                    .param("guardedSymbolFqn", f.guardedSymbolFqn())
                    .param("guardedFlowStep", f.guardedFlowStep())
                    .param("guardedEdgeType", f.guardedEdgeType())
                    .param("gateKind", f.gateKind())
                    .param("gateKey", f.gateKey())
                    .param("requiredValue", f.requiredValue())
                    .param("requiredOperator", f.requiredOperator())
                    .param("effectWhenFail", f.effectWhenFail())
                    .param("skipLogPattern", f.skipLogPattern())
                    .param("testPrecondition", f.testPrecondition())
                    .param("evidenceSource", f.evidenceSource())
                    .param("yamlPath", f.yamlPath())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeValidationHintFacts(FactBatch batch) {
        String sql = """
                INSERT INTO validation_hint_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, flow_step, hint_kind,
                   hint_value, linked_symbol_fqn, env_lane)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :flowStep, :hintKind,
                        :hintValue, :linkedSymbolFqn, :envLane)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ValidationHintFact f : batch.validationHintFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("flowStep", f.flowStep())
                    .param("hintKind", f.hintKind())
                    .param("hintValue", f.hintValue())
                    .param("linkedSymbolFqn", f.linkedSymbolFqn())
                    .param("envLane", f.envLane())
                    .update();
        }
    }

    private void writeExternalEndpointFacts(FactBatch batch) {
        String sql = """
                INSERT INTO external_endpoint_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, endpoint_id, partner_slug,
                   operation, http_method, url_template, url_resolved, env_lane, boundary,
                   config_key, yaml_path, caller_class_fqn, client_class_fqn, flow_step,
                   auth_scheme, evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :endpointId,
                        :partnerSlug, :operation, :httpMethod, :urlTemplate, :urlResolved,
                        :envLane, :boundary, :configKey, :yamlPath, :callerClassFqn,
                        :clientClassFqn, :flowStep, :authScheme, :evidenceSource, :confidence,
                        :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ExternalEndpointFact f : batch.externalEndpointFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("endpointId", f.endpointId())
                    .param("partnerSlug", f.partnerSlug())
                    .param("operation", f.operation())
                    .param("httpMethod", f.httpMethod())
                    .param("urlTemplate", f.urlTemplate())
                    .param("urlResolved", f.urlResolved())
                    .param("envLane", f.envLane())
                    .param("boundary", f.boundary())
                    .param("configKey", f.configKey())
                    .param("yamlPath", f.yamlPath())
                    .param("callerClassFqn", f.callerClassFqn())
                    .param("clientClassFqn", f.clientClassFqn())
                    .param("flowStep", f.flowStep())
                    .param("authScheme", f.authScheme())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeExternalCallSiteFacts(FactBatch batch) {
        String sql = """
                INSERT INTO external_call_site_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, source_symbol,
                   config_accessor, config_prefix, config_property, http_client_type,
                   http_client_method, http_method, endpoint_id, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :sourceSymbol,
                        :configAccessor, :configPrefix, :configProperty, :httpClientType,
                        :httpClientMethod, :httpMethod, :endpointId, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ExternalCallSiteFact f : batch.externalCallSiteFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("sourceSymbol", f.sourceSymbol())
                    .param("configAccessor", f.configAccessor())
                    .param("configPrefix", f.configPrefix())
                    .param("configProperty", f.configProperty())
                    .param("httpClientType", f.httpClientType())
                    .param("httpClientMethod", f.httpClientMethod())
                    .param("httpMethod", f.httpMethod())
                    .param("endpointId", f.endpointId())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeDataObjectFacts(FactBatch batch) {
        String sql = """
                INSERT INTO data_object_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, entity_fqn, domain_fqn,
                   store_type, physical_name, catalog_or_keyspace, collection_or_table_kind,
                   evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :entityFqn, :domainFqn,
                        :storeType, :physicalName, :catalogOrKeyspace, :collectionOrTableKind,
                        :evidenceSource, :confidence, :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.DataObjectFact f : batch.dataObjectFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("entityFqn", f.entityFqn())
                    .param("domainFqn", f.domainFqn())
                    .param("storeType", f.storeType())
                    .param("physicalName", f.physicalName())
                    .param("catalogOrKeyspace", f.catalogOrKeyspace())
                    .param("collectionOrTableKind", f.collectionOrTableKind())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeAccessorMethodFacts(FactBatch batch) {
        String sql = """
                INSERT INTO accessor_method_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, accessor_kind, accessor_fqn,
                   method_name, operation, entity_fqn, domain_fqn, store_type, physical_name,
                   evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :accessorKind, :accessorFqn,
                        :methodName, :operation, :entityFqn, :domainFqn, :storeType, :physicalName,
                        :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.AccessorMethodFact f : batch.accessorMethodFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("accessorKind", f.accessorKind())
                    .param("accessorFqn", f.accessorFqn())
                    .param("methodName", f.methodName())
                    .param("operation", f.operation())
                    .param("entityFqn", f.entityFqn())
                    .param("domainFqn", f.domainFqn())
                    .param("storeType", f.storeType())
                    .param("physicalName", f.physicalName())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeSchemaObjectFacts(FactBatch batch) {
        String sql = """
                INSERT INTO schema_object_facts
                  (org_id, repo, service_id, commit_sha, store_type, physical_name,
                   catalog_or_keyspace, ddl_path, evidence_source)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :storeType, :physicalName,
                        :catalogOrKeyspace, :ddlPath, :evidenceSource)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.SchemaObjectFact f : batch.schemaObjectFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("storeType", f.storeType())
                    .param("physicalName", f.physicalName())
                    .param("catalogOrKeyspace", f.catalogOrKeyspace())
                    .param("ddlPath", f.ddlPath())
                    .param("evidenceSource", f.evidenceSource())
                    .update();
        }
    }

    private void writeEntryTriggerFacts(FactBatch batch) {
        String sql = """
                INSERT INTO entry_trigger_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, trigger_id, trigger_kind,
                   direction, env_lane, actor, boundary, http_method, path_pattern,
                   linked_handler_fqn, linked_method, flow_step, source_ref,
                   evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :triggerId, :triggerKind,
                        :direction, :envLane, :actor, :boundary, :httpMethod, :pathPattern,
                        :linkedHandlerFqn, :linkedMethod, :flowStep, :sourceRef,
                        :evidenceSource, :confidence, :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.EntryTriggerFact f : batch.entryTriggerFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("triggerId", f.triggerId())
                    .param("triggerKind", f.triggerKind())
                    .param("direction", f.direction())
                    .param("envLane", f.envLane())
                    .param("actor", f.actor())
                    .param("boundary", f.boundary())
                    .param("httpMethod", f.httpMethod())
                    .param("pathPattern", f.pathPattern())
                    .param("linkedHandlerFqn", f.linkedHandlerFqn())
                    .param("linkedMethod", f.linkedMethod())
                    .param("flowStep", f.flowStep())
                    .param("sourceRef", f.sourceRef())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeAsyncRetryPathFacts(FactBatch batch) {
        String sql = """
                INSERT INTO async_retry_path_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, env_lane, module_name,
                   linked_topic, bq_dataset, bq_table, source_ref, evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :envLane, :moduleName,
                        :linkedTopic, :bqDataset, :bqTable, :sourceRef, :evidenceSource, :confidence,
                        :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.AsyncRetryPathFact f : batch.asyncRetryPathFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("envLane", f.envLane())
                    .param("moduleName", f.moduleName())
                    .param("linkedTopic", f.linkedTopic())
                    .param("bqDataset", f.bqDataset())
                    .param("bqTable", f.bqTable())
                    .param("sourceRef", f.sourceRef())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeMavenModuleFacts(FactBatch batch) {
        if (batch.mavenModuleFacts().isEmpty()) {
            return;
        }
        db.sql("""
                DELETE FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("serviceId", batch.serviceId())
                .param("commitSha", batch.commitSha())
                .update();

        String sql = """
                INSERT INTO maven_module_facts
                  (org_id, repo, service_id, commit_sha, module_path, relative_pom_path,
                   group_id, artifact_id, version, packaging, parent_group_id, parent_artifact_id,
                   parent_version, is_root_module, resolution_status, evidence_source, indexed_at)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :modulePath, :relativePomPath,
                        :groupId, :artifactId, :version, :packaging, :parentGroupId, :parentArtifactId,
                        :parentVersion, :isRootModule, :resolutionStatus, :evidenceSource, NOW())
                """;
        for (FactBatch.MavenModuleFact f : batch.mavenModuleFacts()) {
            db.sql(sql)
                    .param("orgId", f.orgId())
                    .param("repo", f.repo())
                    .param("serviceId", f.serviceId())
                    .param("commitSha", f.commitSha())
                    .param("modulePath", f.modulePath())
                    .param("relativePomPath", f.relativePomPath())
                    .param("groupId", f.groupId())
                    .param("artifactId", f.artifactId())
                    .param("version", f.version())
                    .param("packaging", f.packaging())
                    .param("parentGroupId", f.parentGroupId())
                    .param("parentArtifactId", f.parentArtifactId())
                    .param("parentVersion", f.parentVersion())
                    .param("isRootModule", f.rootModule())
                    .param("resolutionStatus", f.resolutionStatus())
                    .param("evidenceSource", f.evidenceSource())
                    .update();
        }
    }

    private void writeMavenDependencyFacts(FactBatch batch) {
        if (batch.mavenDependencyFacts().isEmpty()) {
            return;
        }
        db.sql("""
                DELETE FROM maven_dependency_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("serviceId", batch.serviceId())
                .param("commitSha", batch.commitSha())
                .update();

        String sql = """
                INSERT INTO maven_dependency_facts
                  (org_id, repo, service_id, commit_sha, from_module_path, to_group_id, to_artifact_id,
                   to_version, version_literal, scope, optional, transitive, resolved, unresolved_reason,
                   linked_service_id, linked_repo, link_source, cross_repo, evidence_source, confidence, indexed_at)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :fromModulePath, :toGroupId, :toArtifactId,
                        :toVersion, :versionLiteral, :scope, :optional, :transitive, :resolved, :unresolvedReason,
                        :linkedServiceId, :linkedRepo, :linkSource, :crossRepo, :evidenceSource, :confidence, NOW())
                """;
        for (FactBatch.MavenDependencyFact f : batch.mavenDependencyFacts()) {
            db.sql(sql)
                    .param("orgId", f.orgId())
                    .param("repo", f.repo())
                    .param("serviceId", f.serviceId())
                    .param("commitSha", f.commitSha())
                    .param("fromModulePath", f.fromModulePath())
                    .param("toGroupId", f.toGroupId())
                    .param("toArtifactId", f.toArtifactId())
                    .param("toVersion", f.toVersion())
                    .param("versionLiteral", f.versionLiteral() != null ? f.versionLiteral() : "")
                    .param("scope", f.scope())
                    .param("optional", f.optional())
                    .param("transitive", f.transitive())
                    .param("resolved", f.resolved())
                    .param("unresolvedReason", f.unresolvedReason())
                    .param("linkedServiceId", f.linkedServiceId())
                    .param("linkedRepo", f.linkedRepo())
                    .param("linkSource", f.linkSource())
                    .param("crossRepo", f.crossRepo())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeConsistencyScenarioFacts(FactBatch batch) {
        String sql = """
                INSERT INTO consistency_scenario_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, scenario_id, pattern,
                   scope_kind, scope_ref, primary_store, primary_physical, correlation_keys,
                   participants, poll_strategy, invariants, evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :scenarioId, :pattern,
                        :scopeKind, :scopeRef, :primaryStore, :primaryPhysical, :correlationKeys::jsonb,
                        :participants::jsonb, :pollStrategy::jsonb, :invariants::jsonb,
                        :evidenceSource, :confidence, :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ConsistencyScenarioFact f : batch.consistencyScenarioFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("scenarioId", f.scenarioId())
                    .param("pattern", f.pattern())
                    .param("scopeKind", f.scopeKind())
                    .param("scopeRef", f.scopeRef())
                    .param("primaryStore", f.primaryStore())
                    .param("primaryPhysical", f.primaryPhysical())
                    .param("correlationKeys", f.correlationKeys() != null ? f.correlationKeys() : "[]")
                    .param("participants", f.participants())
                    .param("pollStrategy", f.pollStrategy())
                    .param("invariants", f.invariants())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeContractOperationFacts(FactBatch batch) {
        String sql = """
                INSERT INTO contract_operation_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, operation_id, spec_domain,
                   spec_file, openapi_version, operation_id_openapi, http_method, path_template,
                   path_normalized, summary, tags, request_schema_ref, response_schema_ref,
                   request_field_summary, response_field_summary, server_urls, mapped_service_name,
                   evidence_source, confidence, attributes)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :operationId, :specDomain,
                        :specFile, :openapiVersion, :operationIdOpenapi, :httpMethod, :pathTemplate,
                        :pathNormalized, :summary, :tags::jsonb, :requestSchemaRef, :responseSchemaRef,
                        :requestFieldSummary::jsonb, :responseFieldSummary::jsonb, :serverUrls::jsonb,
                        :mappedServiceName, :evidenceSource, :confidence, :attributes::jsonb)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ContractOperationFact f : batch.contractOperationFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("operationId", f.operationId())
                    .param("specDomain", f.specDomain())
                    .param("specFile", f.specFile())
                    .param("openapiVersion", f.openapiVersion())
                    .param("operationIdOpenapi", f.operationIdOpenapi())
                    .param("httpMethod", f.httpMethod())
                    .param("pathTemplate", f.pathTemplate())
                    .param("pathNormalized", f.pathNormalized())
                    .param("summary", f.summary())
                    .param("tags", f.tagsJson() != null ? f.tagsJson() : "[]")
                    .param("requestSchemaRef", f.requestSchemaRef())
                    .param("responseSchemaRef", f.responseSchemaRef())
                    .param("requestFieldSummary", f.requestFieldSummaryJson() != null ? f.requestFieldSummaryJson() : "[]")
                    .param("responseFieldSummary", f.responseFieldSummaryJson() != null ? f.responseFieldSummaryJson() : "[]")
                    .param("serverUrls", f.serverUrlsJson() != null ? f.serverUrlsJson() : "[]")
                    .param("mappedServiceName", f.mappedServiceName())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .param("attributes", f.attributes() != null ? f.attributes() : "{}")
                    .update();
        }
    }

    private void writeContractSchemaFacts(FactBatch batch) {
        String sql = """
                INSERT INTO contract_schema_facts
                  (org_id, repo, service_id, commit_sha, schema_id, schema_title, schema_type,
                   top_level_fields, required_fields, nested_field_paths, spec_file, evidence_source)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :schemaId, :schemaTitle, :schemaType,
                        :topLevelFields::jsonb, :requiredFields::jsonb, :nestedFieldPaths::jsonb,
                        :specFile, :evidenceSource)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.ContractSchemaFact f : batch.contractSchemaFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("schemaId", f.schemaId())
                    .param("schemaTitle", f.schemaTitle())
                    .param("schemaType", f.schemaType())
                    .param("topLevelFields", f.topLevelFieldsJson() != null ? f.topLevelFieldsJson() : "[]")
                    .param("requiredFields", f.requiredFieldsJson() != null ? f.requiredFieldsJson() : "[]")
                    .param("nestedFieldPaths", f.nestedFieldPathsJson() != null ? f.nestedFieldPathsJson() : "[]")
                    .param("specFile", f.specFile())
                    .param("evidenceSource", f.evidenceSource())
                    .update();
        }
    }

    private void writeTestHttpCallFacts(FactBatch batch) {
        String sql = """
                INSERT INTO test_http_call_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, file_path, source_symbol,
                   http_method, path, path_normalized, path_constant_ref, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :filePath, :sourceSymbol,
                        :httpMethod, :path, :pathNormalized, :pathConstantRef, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.TestHttpCallFact f : batch.testHttpCallFacts()) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("filePath", f.filePath())
                    .param("sourceSymbol", f.sourceSymbol())
                    .param("httpMethod", f.httpMethod())
                    .param("path", f.path())
                    .param("pathNormalized", f.pathNormalized())
                    .param("pathConstantRef", f.pathConstantRef())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence", f.confidence())
                    .update();
        }
    }

    private void writeParsedModels(FactBatch batch, List<ParsedModel> models) {
        if (models.isEmpty()) return;
        String baseId = batch.orgId() + "/" + batch.repo() + "/"
                + batch.serviceId() + "/" + batch.commitSha();
        var collection = mongo.getCollection("parsed_models");
        try {
            collection.deleteMany(new Document("orgId", batch.orgId())
                    .append("repo", batch.repo())
                    .append("serviceId", batch.serviceId())
                    .append("commitSha", batch.commitSha()));

            List<List<ParsedModel>> chunks = partitionModelsBySerializedSize(models);
            int totalChunks = chunks.size();
            for (int i = 0; i < totalChunks; i++) {
                String docId = totalChunks == 1 ? baseId : baseId + "/chunk/" + i;
                Document doc = new Document();
                doc.put("_id", docId);
                doc.put("orgId", batch.orgId());
                doc.put("repo", batch.repo());
                doc.put("serviceId", batch.serviceId());
                doc.put("commitSha", batch.commitSha());
                doc.put("indexedAt", Instant.now().toString());
                if (totalChunks > 1) {
                    doc.put("chunkIndex", i);
                    doc.put("totalChunks", totalChunks);
                }
                doc.put("models", mapper.writeValueAsString(chunks.get(i)));

                collection.replaceOne(new Document("_id", docId), doc, new ReplaceOptions().upsert(true));
            }
            if (totalChunks > 1) {
                log.info("Wrote {} Mongo parsed_models chunks for {}/{} ({} files)",
                        totalChunks, batch.serviceId(), batch.commitSha(), models.size());
            }
        } catch (Exception ex) {
            if (isDocumentTooLarge(ex)) {
                log.warn("Skipping Mongo parsed_models archive for {}/{} ({} files): exceeds 16MB BSON limit — Postgres facts were persisted",
                        batch.serviceId(), batch.commitSha(), models.size());
                return;
            }
            log.error("MongoDB write failed for {}/{}: {}",
                    batch.serviceId(), batch.commitSha(), ex.getMessage());
            throw new RuntimeException("MongoDB write failed", ex);
        }
    }

    static List<List<ParsedModel>> partitionModelsBySerializedSize(
            List<ParsedModel> models, ObjectMapper mapper) throws JsonProcessingException {
        List<List<ParsedModel>> chunks = new ArrayList<>();
        List<ParsedModel> current = new ArrayList<>();
        int currentSize = 2;
        for (ParsedModel model : models) {
            String json = mapper.writeValueAsString(model);
            int modelSize = json.length() + (current.isEmpty() ? 0 : 1);
            if (!current.isEmpty() && currentSize + modelSize > PARSED_MODELS_CHUNK_TARGET_CHARS) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSize = 2;
            }
            current.add(model);
            currentSize += modelSize;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private List<List<ParsedModel>> partitionModelsBySerializedSize(List<ParsedModel> models)
            throws JsonProcessingException {
        return partitionModelsBySerializedSize(models, mapper);
    }

    static List<FactBatch.PubSubResourceFact> dedupePubSubResourceFacts(
            List<FactBatch.PubSubResourceFact> facts) {
        Map<String, FactBatch.PubSubResourceFact> byKey = new LinkedHashMap<>();
        for (FactBatch.PubSubResourceFact fact : facts) {
            byKey.merge(pubsubResourceUniqueKey(fact), fact, DualWriteService::preferPubSubResourceFact);
        }
        return List.copyOf(byKey.values());
    }

    static String pubsubResourceUniqueKey(FactBatch.PubSubResourceFact f) {
        return f.resourceKind() + "|" + f.shortId() + "|" + f.envLane() + "|" + f.role() + "|"
                + nullToEmpty(f.springKey()) + "|" + f.yamlPath() + "|"
                + nullToEmpty(f.linkedClassFqn());
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static FactBatch.PubSubResourceFact preferPubSubResourceFact(
            FactBatch.PubSubResourceFact left, FactBatch.PubSubResourceFact right) {
        if (left.confidence() != right.confidence()) {
            return left.confidence() >= right.confidence() ? left : right;
        }
        if (left.linkedMethod() != null && right.linkedMethod() == null) {
            return left;
        }
        if (right.linkedMethod() != null && left.linkedMethod() == null) {
            return right;
        }
        return left;
    }

    private static boolean isDocumentTooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof BsonMaximumSizeExceededException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("document size is larger than maximum")) {
                return true;
            }
        }
        return false;
    }
}
