package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.query.CatalogResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypeFqnResolverTest {

    private static final String LIBRARY_DAO =
            "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao";

    @Mock JdbcClient db;
    @Mock CatalogResolverService catalogResolver;

    TypeFqnResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TypeFqnResolver(db, catalogResolver);
    }

    @Test
    void resolve_usesExplicitImport() {
        String source = """
                package com.quotient.platform.partneradapter.lib.adapter;
                import com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao;
                """;
        ImportIndex imports = ImportIndex.build(source);
        var ctx = new TypeFqnResolver.CompilationContext(
                "acme", "svc-adapter",
                "com.quotient.platform.partneradapter.lib.adapter.OfferBaseAdapter");

        var resolved = resolver.resolve("PartnerOfferCallRecorderDao", imports, ctx);

        assertThat(resolved.fqn()).isEqualTo(LIBRARY_DAO);
        assertThat(resolved.tier()).isEqualTo("IMPORT");
        assertThat(resolved.confidence()).isEqualTo(0.95);
    }

    @Test
    void resolve_usesCatalogWhenImportMissing() {
        when(catalogResolver.findTypeFqnBySimpleName(eq("acme"), anyList(), eq("PartnerOfferCallRecorderDao")))
                .thenReturn(Optional.of(LIBRARY_DAO));

        ImportIndex imports = ImportIndex.build("package com.example.handler;");
        var ctx = new TypeFqnResolver.CompilationContext("acme", null, "com.example.handler.Handler");

        var resolved = resolver.resolve("PartnerOfferCallRecorderDao", imports, ctx);

        assertThat(resolved.fqn()).isEqualTo(LIBRARY_DAO);
        assertThat(resolved.tier()).isEqualTo("CATALOG");
        assertThat(resolved.confidence()).isEqualTo(0.80);
    }

    @Test
    void resolve_usesClasspathBeforeCatalog() {
        ImportIndex imports = ImportIndex.build("package com.example.handler;");
        var ctx = new TypeFqnResolver.CompilationContext(
                "acme", "svc-adapter", "com.example.handler.Handler",
                List.of("platform-data"),
                Map.of("PartnerOfferCallRecorderDao", LIBRARY_DAO));

        var resolved = resolver.resolve("PartnerOfferCallRecorderDao", imports, ctx);

        assertThat(resolved.fqn()).isEqualTo(LIBRARY_DAO);
        assertThat(resolved.tier()).isEqualTo("CLASSPATH");
        assertThat(resolved.confidence()).isEqualTo(0.90);
    }

    @Test
    void resolve_samePackageFallback() {
        when(catalogResolver.findTypeFqnBySimpleName(anyString(), anyList(), anyString()))
                .thenReturn(Optional.empty());

        ImportIndex imports = ImportIndex.build("package io.orders;");
        var ctx = new TypeFqnResolver.CompilationContext("acme", "svc-1", "io.orders.OrderController");

        var resolved = resolver.resolve("OrderService", imports, ctx);

        assertThat(resolved.fqn()).isEqualTo("io.orders.OrderService");
        assertThat(resolved.tier()).isEqualTo("SAME_PACKAGE");
        assertThat(resolved.confidence()).isEqualTo(0.50);
    }

    @Test
    void resolve_preservesQualifiedName() {
        ImportIndex imports = ImportIndex.build("package io.orders;");
        var ctx = new TypeFqnResolver.CompilationContext("acme", "svc-1", "io.orders.OrderController");

        var resolved = resolver.resolve("io.billing.Client", imports, ctx);

        assertThat(resolved.fqn()).isEqualTo("io.billing.Client");
        assertThat(resolved.tier()).isEqualTo("QUALIFIED");
    }
}
