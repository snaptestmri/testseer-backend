package io.testseer.backend.ingestion;

import java.util.List;

public record FactBatch(
        String jobId,
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        String snapshotType,       // "BASELINE" | "DELTA"
        List<SymbolFact> symbolFacts,
        List<OutboundCallFact> outboundCallFacts,
        List<PeripheralFact> peripheralFacts,
        List<UnsupportedConstructFact> unsupportedConstructFacts,
        List<PubSubResourceFact> pubsubResourceFacts,
        List<MessageSchemaFact> messageSchemaFacts,
        List<DataAccessFact> dataAccessFacts,
        List<FlowGateFact> flowGateFacts,
        List<ValidationHintFact> validationHintFacts,
        List<ExternalEndpointFact> externalEndpointFacts,
        List<ExternalCallSiteFact> externalCallSiteFacts,
        List<DataObjectFact> dataObjectFacts,
        List<AccessorMethodFact> accessorMethodFacts,
        List<SchemaObjectFact> schemaObjectFacts,
        List<EntryTriggerFact> entryTriggerFacts,
        List<ConsistencyScenarioFact> consistencyScenarioFacts,
        List<ContractOperationFact> contractOperationFacts,
        List<ContractSchemaFact> contractSchemaFacts,
        List<TestHttpCallFact> testHttpCallFacts,
        List<AsyncRetryPathFact> asyncRetryPathFacts,
        List<MavenModuleFact> mavenModuleFacts,
        List<MavenDependencyFact> mavenDependencyFacts
) {
    public static FactBatch create(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts,
            List<PubSubResourceFact> pubsubResourceFacts,
            List<MessageSchemaFact> messageSchemaFacts,
            List<DataAccessFact> dataAccessFacts,
            List<FlowGateFact> flowGateFacts,
            List<ValidationHintFact> validationHintFacts,
            List<ExternalEndpointFact> externalEndpointFacts,
            List<ExternalCallSiteFact> externalCallSiteFacts,
            List<DataObjectFact> dataObjectFacts,
            List<AccessorMethodFact> accessorMethodFacts,
            List<SchemaObjectFact> schemaObjectFacts,
            List<EntryTriggerFact> entryTriggerFacts,
            List<ConsistencyScenarioFact> consistencyScenarioFacts,
            List<ContractOperationFact> contractOperationFacts,
            List<ContractSchemaFact> contractSchemaFacts,
            List<TestHttpCallFact> testHttpCallFacts,
            List<AsyncRetryPathFact> asyncRetryPathFacts,
            List<MavenModuleFact> mavenModuleFacts,
            List<MavenDependencyFact> mavenDependencyFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts != null ? pubsubResourceFacts : List.of(),
                messageSchemaFacts != null ? messageSchemaFacts : List.of(),
                dataAccessFacts != null ? dataAccessFacts : List.of(),
                flowGateFacts != null ? flowGateFacts : List.of(),
                validationHintFacts != null ? validationHintFacts : List.of(),
                externalEndpointFacts != null ? externalEndpointFacts : List.of(),
                externalCallSiteFacts != null ? externalCallSiteFacts : List.of(),
                dataObjectFacts != null ? dataObjectFacts : List.of(),
                accessorMethodFacts != null ? accessorMethodFacts : List.of(),
                schemaObjectFacts != null ? schemaObjectFacts : List.of(),
                entryTriggerFacts != null ? entryTriggerFacts : List.of(),
                consistencyScenarioFacts != null ? consistencyScenarioFacts : List.of(),
                contractOperationFacts != null ? contractOperationFacts : List.of(),
                contractSchemaFacts != null ? contractSchemaFacts : List.of(),
                testHttpCallFacts != null ? testHttpCallFacts : List.of(),
                asyncRetryPathFacts != null ? asyncRetryPathFacts : List.of(),
                mavenModuleFacts != null ? mavenModuleFacts : List.of(),
                mavenDependencyFacts != null ? mavenDependencyFacts : List.of()
        );
    }

    /** Backward-compatible: catalog + entry triggers without consistency scenarios. */
    public static FactBatch create(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts,
            List<PubSubResourceFact> pubsubResourceFacts,
            List<MessageSchemaFact> messageSchemaFacts,
            List<DataAccessFact> dataAccessFacts,
            List<FlowGateFact> flowGateFacts,
            List<ValidationHintFact> validationHintFacts,
            List<ExternalEndpointFact> externalEndpointFacts,
            List<ExternalCallSiteFact> externalCallSiteFacts,
            List<DataObjectFact> dataObjectFacts,
            List<AccessorMethodFact> accessorMethodFacts,
            List<SchemaObjectFact> schemaObjectFacts,
            List<EntryTriggerFact> entryTriggerFacts,
            List<ConsistencyScenarioFact> consistencyScenarioFacts) {
        return create(jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts,
                validationHintFacts, externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts, entryTriggerFacts,
                consistencyScenarioFacts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Backward-compatible: entry triggers without consistency scenarios. */
    public static FactBatch create(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts,
            List<PubSubResourceFact> pubsubResourceFacts,
            List<MessageSchemaFact> messageSchemaFacts,
            List<DataAccessFact> dataAccessFacts,
            List<FlowGateFact> flowGateFacts,
            List<ValidationHintFact> validationHintFacts,
            List<ExternalEndpointFact> externalEndpointFacts,
            List<ExternalCallSiteFact> externalCallSiteFacts,
            List<DataObjectFact> dataObjectFacts,
            List<AccessorMethodFact> accessorMethodFacts,
            List<SchemaObjectFact> schemaObjectFacts,
            List<EntryTriggerFact> entryTriggerFacts) {
        return create(jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts,
                validationHintFacts, externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts, entryTriggerFacts,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static FactBatch create(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts,
            List<PubSubResourceFact> pubsubResourceFacts,
            List<MessageSchemaFact> messageSchemaFacts,
            List<DataAccessFact> dataAccessFacts,
            List<FlowGateFact> flowGateFacts,
            List<ValidationHintFact> validationHintFacts,
            List<ExternalEndpointFact> externalEndpointFacts,
            List<ExternalCallSiteFact> externalCallSiteFacts) {
        return create(jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts,
                validationHintFacts, externalEndpointFacts, externalCallSiteFacts,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Backward-compatible factory for tests that only supply core + messaging facts. */
    public static FactBatch create(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts,
            List<PubSubResourceFact> pubsubResourceFacts,
            List<MessageSchemaFact> messageSchemaFacts,
            List<DataAccessFact> dataAccessFacts,
            List<FlowGateFact> flowGateFacts,
            List<ValidationHintFact> validationHintFacts) {
        return create(jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts,
                validationHintFacts, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public FactBatch withEntryTriggers(List<EntryTriggerFact> entryTriggerFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts != null ? entryTriggerFacts : List.of(),
                consistencyScenarioFacts,
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts,
                asyncRetryPathFacts,
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withConsistencyScenarios(List<ConsistencyScenarioFact> consistencyScenarioFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts,
                consistencyScenarioFacts != null ? consistencyScenarioFacts : List.of(),
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts,
                asyncRetryPathFacts,
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withCatalogFacts(
            List<DataObjectFact> dataObjectFacts,
            List<AccessorMethodFact> accessorMethodFacts,
            List<SchemaObjectFact> schemaObjectFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts != null ? dataObjectFacts : List.of(),
                accessorMethodFacts != null ? accessorMethodFacts : List.of(),
                schemaObjectFacts != null ? schemaObjectFacts : this.schemaObjectFacts(),
                entryTriggerFacts,
                consistencyScenarioFacts,
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts,
                asyncRetryPathFacts,
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withContractFacts(
            List<ContractOperationFact> contractOperationFacts,
            List<ContractSchemaFact> contractSchemaFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts,
                consistencyScenarioFacts,
                contractOperationFacts != null ? contractOperationFacts : List.of(),
                contractSchemaFacts != null ? contractSchemaFacts : List.of(),
                testHttpCallFacts,
                asyncRetryPathFacts,
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withTestHttpCallFacts(List<TestHttpCallFact> testHttpCallFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts,
                consistencyScenarioFacts,
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts != null ? testHttpCallFacts : List.of(),
                asyncRetryPathFacts,
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withAsyncRetryPaths(List<AsyncRetryPathFact> asyncRetryPathFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts,
                consistencyScenarioFacts,
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts,
                asyncRetryPathFacts != null ? asyncRetryPathFacts : List.of(),
                mavenModuleFacts,
                mavenDependencyFacts
        );
    }

    public FactBatch withMavenFacts(
            List<MavenModuleFact> mavenModuleFacts,
            List<MavenDependencyFact> mavenDependencyFacts) {
        return new FactBatch(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                pubsubResourceFacts, messageSchemaFacts, dataAccessFacts, flowGateFacts, validationHintFacts,
                externalEndpointFacts, externalCallSiteFacts,
                dataObjectFacts, accessorMethodFacts, schemaObjectFacts,
                entryTriggerFacts,
                consistencyScenarioFacts,
                contractOperationFacts,
                contractSchemaFacts,
                testHttpCallFacts,
                asyncRetryPathFacts,
                mavenModuleFacts != null ? mavenModuleFacts : List.of(),
                mavenDependencyFacts != null ? mavenDependencyFacts : List.of()
        );
    }

    /** Backward-compatible factory for tests that only supply core facts. */
    public static FactBatch core(
            String jobId, String orgId, String repo, String serviceId, String commitSha,
            String snapshotType,
            List<SymbolFact> symbolFacts,
            List<OutboundCallFact> outboundCallFacts,
            List<PeripheralFact> peripheralFacts,
            List<UnsupportedConstructFact> unsupportedConstructFacts) {
        return create(jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundCallFacts, peripheralFacts, unsupportedConstructFacts,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public record SymbolFact(
            String filePath, String symbolFqn, String symbolKind,
            String attributes, String evidenceSource, double confidence
    ) {}

    public record OutboundCallFact(
            String sourceSymbol, String httpMethod, String path,
            String evidenceSource, double confidence
    ) {}

    public record PeripheralFact(
            String peripheralType, int detectionTier,
            String detectionSignals, String prerequisiteText, String reasonCode
    ) {}

    public record UnsupportedConstructFact(
            String filePath, String reasonCode, String detail
    ) {}

    public record PubSubResourceFact(
            String resourceKind,
            String shortId,
            String envLane,
            String envProfile,
            String gcpProject,
            String fullResourceId,
            String role,
            String springKey,
            String yamlPath,
            String moduleName,
            String linkedClassFqn,
            String linkedMethod,
            String workloadName,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record MessageSchemaFact(
            String envelopeType,
            String payloadProto,
            String payloadFields,
            String payloadEnums,
            String linkedClassFqn,
            String linkedMethod,
            String direction,
            String topicShortId,
            String unpackExpression,
            String protoFile,
            String evidenceSource,
            double confidence
    ) {}

    public record DataAccessFact(
            String handlerClassFqn,
            String handlerMethod,
            String operation,
            String storeType,
            String tableOrEntity,
            String repositoryFqn,
            String daoMethod,
            String correlationKeys,
            String validationHint,
            String evidenceSource,
            double confidence,
            String entityFqn,
            String domainFqn,
            String accessorFqn,
            String accessorKind,
            String catalogRef,
            String secondaryStores
    ) {
        public static DataAccessFact touchpoint(
                String handlerClassFqn,
                String handlerMethod,
                String operation,
                String storeType,
                String tableOrEntity,
                String repositoryFqn,
                String daoMethod,
                String correlationKeys,
                String validationHint,
                String evidenceSource,
                double confidence) {
            return new DataAccessFact(
                    handlerClassFqn, handlerMethod, operation, storeType, tableOrEntity,
                    repositoryFqn, daoMethod, correlationKeys, validationHint, evidenceSource, confidence,
                    null, null, null, null, null, null);
        }

        public static DataAccessFact linked(
                String handlerClassFqn,
                String handlerMethod,
                String operation,
                String storeType,
                String tableOrEntity,
                String repositoryFqn,
                String daoMethod,
                String correlationKeys,
                String validationHint,
                String evidenceSource,
                double confidence,
                String entityFqn,
                String domainFqn,
                String accessorFqn,
                String accessorKind,
                String catalogRef,
                String secondaryStores) {
            return new DataAccessFact(
                    handlerClassFqn, handlerMethod, operation, storeType, tableOrEntity,
                    repositoryFqn, daoMethod, correlationKeys, validationHint, evidenceSource, confidence,
                    entityFqn, domainFqn, accessorFqn, accessorKind, catalogRef, secondaryStores);
        }
    }

    public record FlowGateFact(
            String envLane,
            String guardedSymbolFqn,
            String guardedFlowStep,
            String guardedEdgeType,
            String gateKind,
            String gateKey,
            String requiredValue,
            String requiredOperator,
            String effectWhenFail,
            String skipLogPattern,
            String testPrecondition,
            String evidenceSource,
            String yamlPath,
            double confidence
    ) {}

    public record ValidationHintFact(
            String flowStep,
            String hintKind,
            String hintValue,
            String linkedSymbolFqn,
            String envLane
    ) {}

    public record ExternalEndpointFact(
            String endpointId,
            String partnerSlug,
            String operation,
            String httpMethod,
            String urlTemplate,
            String urlResolved,
            String envLane,
            String boundary,
            String configKey,
            String yamlPath,
            String callerClassFqn,
            String clientClassFqn,
            String flowStep,
            String authScheme,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record ExternalCallSiteFact(
            String sourceSymbol,
            String configAccessor,
            String configPrefix,
            String configProperty,
            String httpClientType,
            String httpClientMethod,
            String httpMethod,
            String endpointId,
            String evidenceSource,
            double confidence
    ) {}

    public record DataObjectFact(
            String entityFqn,
            String domainFqn,
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            String collectionOrTableKind,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record AccessorMethodFact(
            String accessorKind,
            String accessorFqn,
            String methodName,
            String operation,
            String entityFqn,
            String domainFqn,
            String storeType,
            String physicalName,
            String evidenceSource,
            double confidence
    ) {}

    public record SchemaObjectFact(
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            String ddlPath,
            String evidenceSource
    ) {}

    public record EntryTriggerFact(
            String triggerId,
            String triggerKind,
            String direction,
            String envLane,
            String actor,
            String boundary,
            String httpMethod,
            String pathPattern,
            String linkedHandlerFqn,
            String linkedMethod,
            String flowStep,
            String sourceRef,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record ConsistencyScenarioFact(
            String scenarioId,
            String pattern,
            String scopeKind,
            String scopeRef,
            String primaryStore,
            String primaryPhysical,
            String correlationKeys,
            String participants,
            String pollStrategy,
            String invariants,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record ContractOperationFact(
            String operationId,
            String specDomain,
            String specFile,
            String openapiVersion,
            String operationIdOpenapi,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String summary,
            String tagsJson,
            String requestSchemaRef,
            String responseSchemaRef,
            String requestFieldSummaryJson,
            String responseFieldSummaryJson,
            String serverUrlsJson,
            String mappedServiceName,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record ContractSchemaFact(
            String schemaId,
            String schemaTitle,
            String schemaType,
            String topLevelFieldsJson,
            String requiredFieldsJson,
            String nestedFieldPathsJson,
            String specFile,
            String evidenceSource
    ) {}

    public record TestHttpCallFact(
            String filePath,
            String sourceSymbol,
            String httpMethod,
            String path,
            String pathNormalized,
            String pathConstantRef,
            String evidenceSource,
            double confidence
    ) {}

    public record AsyncRetryPathFact(
            String envLane,
            String moduleName,
            String linkedTopic,
            String bqDataset,
            String bqTable,
            String sourceRef,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record MavenModuleFact(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String modulePath,
            String relativePomPath,
            String groupId,
            String artifactId,
            String version,
            String packaging,
            String parentGroupId,
            String parentArtifactId,
            String parentVersion,
            boolean rootModule,
            String resolutionStatus,
            String evidenceSource,
            double confidence
    ) {}

    public record MavenDependencyFact(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String fromModulePath,
            String toGroupId,
            String toArtifactId,
            String toVersion,
            String versionLiteral,
            String scope,
            boolean optional,
            boolean transitive,
            boolean resolved,
            String unresolvedReason,
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo,
            String evidenceSource,
            double confidence
    ) {}
}
