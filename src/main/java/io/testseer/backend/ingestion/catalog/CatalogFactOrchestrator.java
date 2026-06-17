package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CatalogFactOrchestrator {

    private final EntityCatalogExtractor entityCatalogExtractor;
    private final RepoGenericExtractor repoGenericExtractor;
    private final DaoMethodExtractor daoMethodExtractor;
    private final CassandraQueryExtractor cassandraQueryExtractor;
    private final MirrorStoreExtractor mirrorStoreExtractor;

    public CatalogFactOrchestrator(
            EntityCatalogExtractor entityCatalogExtractor,
            RepoGenericExtractor repoGenericExtractor,
            DaoMethodExtractor daoMethodExtractor,
            CassandraQueryExtractor cassandraQueryExtractor,
            MirrorStoreExtractor mirrorStoreExtractor) {
        this.entityCatalogExtractor = entityCatalogExtractor;
        this.repoGenericExtractor = repoGenericExtractor;
        this.daoMethodExtractor = daoMethodExtractor;
        this.cassandraQueryExtractor = cassandraQueryExtractor;
        this.mirrorStoreExtractor = mirrorStoreExtractor;
    }

    public CatalogFacts build(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<MessagingFactOrchestrator.SourceFile> sources) {

        List<ProtoSchemaExtractor.JavaSourceFile> javaFiles = sources.stream()
                .map(f -> new ProtoSchemaExtractor.JavaSourceFile(
                        f.path(), f.content(), f.parsedModel().classFqn()))
                .toList();

        List<FactBatch.DataObjectFact> entities = entityCatalogExtractor.extract(javaFiles);
        List<RepoGenericExtractor.RepoLink> repos = repoGenericExtractor.extract(javaFiles);
        entities = repoGenericExtractor.enrichWithRepos(entities, repos);
        List<FactBatch.AccessorMethodFact> accessors = daoMethodExtractor.extract(javaFiles, entities);
        accessors = cassandraQueryExtractor.extractAndMerge(javaFiles, entities, accessors);

        List<MirrorStoreExtractor.MirrorRef> mirrors = mirrorStoreExtractor.extract(javaFiles);
        entities = mirrorStoreExtractor.attachMirrorsToEntities(entities, mirrors, repos);

        return new CatalogFacts(entities, accessors);
    }

    public record CatalogFacts(
            List<FactBatch.DataObjectFact> dataObjectFacts,
            List<FactBatch.AccessorMethodFact> accessorMethodFacts
    ) {}
}
