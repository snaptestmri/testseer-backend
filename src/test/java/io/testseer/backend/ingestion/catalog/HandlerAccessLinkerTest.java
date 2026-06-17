package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import io.testseer.backend.query.CatalogResolverService;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlerAccessLinkerTest {

    @Mock CatalogResolverService catalogResolver;
    @Mock JdbcClient db;
    @Mock WorkspaceCatalogService workspaceCatalog;
    @Mock ServiceRegistryService registryService;
    @Mock LibraryClasspathBuilder classpathBuilder;

    HandlerAccessLinker linker;

    @BeforeEach
    void setUp() {
        TypeFqnResolver typeFqnResolver = new TypeFqnResolver(db, catalogResolver);
        linker = new HandlerAccessLinker(
                catalogResolver, typeFqnResolver, workspaceCatalog, registryService, classpathBuilder);
        when(registryService.getById("svc-adapter")).thenReturn(serviceEntry("partner-adapter-suite"));
        when(workspaceCatalog.pinnedCatalogLibraryIdsForService(eq("acme"), eq("partner-adapter-suite")))
                .thenReturn(List.of("platform-data"));
        when(workspaceCatalog.resolveGithubRoot("acme")).thenReturn(null);
        when(classpathBuilder.forServiceModule(anyString(), anyString(), isNull(), isNull(), anyList()))
                .thenReturn(new LibraryClasspathBuilder.SymbolResolutionContext(
                        "acme", "partner-adapter-suite", null, List.of("platform-data"),
                        null, null, java.util.Map.of()));
    }

    private static ServiceEntry serviceEntry(String serviceName) {
        return new ServiceEntry(
                "svc-adapter", "acme", "riq-partner-adapter-suite", serviceName,
                "service", "MAVEN", List.of(), List.of(), null, true, Instant.now(), Instant.now());
    }

    private static final String ACCESSOR_FQN =
            "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao";
    private static final String ENTITY_FQN =
            "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity";
    private static final String DOMAIN_FQN =
            "com.quotient.platform.domain.offer.PartnerOfferCallRecorder";

    @Test
    void extract_linksSaveToDbWithCatalog() {
        when(catalogResolver.findAccessorMethod(eq("acme"), anyList(), eq(ACCESSOR_FQN), eq("saveToDb")))
                .thenReturn(Optional.of(new CatalogResolverService.AccessorMethodRow(
                        ACCESSOR_FQN, "saveToDb", "WRITE", ENTITY_FQN, DOMAIN_FQN,
                        "MARIADB", "PartnerOfferCallRecorder", null, 0.93)));
        when(catalogResolver.findAccessorMethod(eq("acme"), anyList(), eq(ACCESSOR_FQN), eq("markAllPendingAsProcessed")))
                .thenReturn(Optional.of(new CatalogResolverService.AccessorMethodRow(
                        ACCESSOR_FQN, "markAllPendingAsProcessed", "WRITE", ENTITY_FQN, DOMAIN_FQN,
                        "MARIADB", "PartnerOfferCallRecorder", null, 0.93)));
        when(catalogResolver.findEntityByFqn(eq("acme"), anyList(), eq(ENTITY_FQN)))
                .thenReturn(Optional.of(new CatalogResolverService.CatalogEntry(
                        ENTITY_FQN, DOMAIN_FQN, "MARIADB", "PartnerOfferCallRecorder",
                        "coupons_nextgen", 0.95, null)));

        String java = """
                package com.quotient.platform.partneradapter.lib.adapter;
                import com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao;
                import com.quotient.platform.domain.offer.PartnerOfferCallRecorder;
                public class OfferBaseAdapter {
                    protected final PartnerOfferCallRecorderDao partnerOfferCallRecorderDao;
                    public OfferBaseAdapter(PartnerOfferCallRecorderDao partnerOfferCallRecorderDao) {
                        this.partnerOfferCallRecorderDao = partnerOfferCallRecorderDao;
                    }
                    public void recordSubmission(PartnerOfferCallRecorder partnerOfferCallRecorder) {
                        partnerOfferCallRecorderDao.saveToDb(partnerOfferCallRecorder);
                        partnerOfferCallRecorderDao.markAllPendingAsProcessed(
                                partnerOfferCallRecorder.getPartnerId(), partnerOfferCallRecorder.getOfferId());
                    }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferBaseAdapter.java", java,
                "com.quotient.platform.partneradapter.lib.adapter.OfferBaseAdapter"));

        List<FactBatch.DataAccessFact> facts = linker.extract("acme", "svc-adapter", files);

        assertThat(facts).anyMatch(f ->
                "saveToDb".equals(f.daoMethod())
                        && "WRITE".equals(f.operation())
                        && "PartnerOfferCallRecorder".equals(f.tableOrEntity())
                        && ENTITY_FQN.equals(f.entityFqn())
                        && ACCESSOR_FQN.equals(f.accessorFqn())
                        && "recordSubmission".equals(f.handlerMethod())
                        && "coupons_nextgen".equals(f.catalogRef()));

        assertThat(facts).anyMatch(f ->
                "markAllPendingAsProcessed".equals(f.daoMethod()) && "WRITE".equals(f.operation()));
    }

    @Test
    void extract_emitsPartialFactWhenCatalogMissing() {
        when(catalogResolver.findAccessorMethod(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        String java = """
                package com.example;
                import com.example.FooDao;
                public class Handler {
                    private FooDao fooDao;
                    public void handle() { fooDao.saveToDb(item); }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "Handler.java", java, "com.example.Handler"));

        List<FactBatch.DataAccessFact> facts = linker.extract("acme", "svc-adapter", files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.daoMethod()).isEqualTo("saveToDb");
            assertThat(f.evidenceSource()).isEqualTo("HANDLER_WITHOUT_CATALOG");
            assertThat(f.entityFqn()).isNull();
        });
    }
}
