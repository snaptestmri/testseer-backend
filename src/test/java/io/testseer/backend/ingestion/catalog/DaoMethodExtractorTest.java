package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaoMethodExtractorTest {

    private static final String DAO_INTERFACE = """
            package com.quotient.platform.data.rdb.dataaccess.offer.dao;
            import com.quotient.platform.domain.offer.PartnerOfferCallRecorder;
            public interface PartnerOfferCallRecorderDao {
                Boolean isSubmitted(String partnerId, String offerId);
                void saveToDb(PartnerOfferCallRecorder partnerOfferCallRecorder);
                Boolean markAllPendingAsProcessed(String partnerId, String offerId);
            }
            """;

    private static final String DAO_IMPL = """
            package com.quotient.platform.data.rdb.dataaccess.offer.dao;
            import com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity;
            import com.quotient.platform.data.rdb.repo.readwrite.offer.PartnerOfferCallRecorderWriteRepo;
            import com.quotient.platform.domain.offer.PartnerOfferCallRecorder;
            public class PartnerOfferCallRecorderDaoImpl implements PartnerOfferCallRecorderDao {
                private PartnerOfferCallRecorderWriteRepo partnerOfferCallRecorderWriteRepo;
                public void saveToDb(PartnerOfferCallRecorder partnerOfferCallRecorder) {
                    PartnerOfferCallRecorderEntity entity = mapDomainToEntity(partnerOfferCallRecorder);
                    partnerOfferCallRecorderWriteRepo.save(entity);
                }
                public Boolean markAllPendingAsProcessed(String partnerId, String offerId) {
                    partnerOfferCallRecorderWriteRepo.updatePendingToProcessedQuery(partnerId, offerId);
                    return true;
                }
                private PartnerOfferCallRecorderEntity mapDomainToEntity(PartnerOfferCallRecorder domain) {
                    return PartnerOfferCallRecorderEntity.builder().offerId(domain.getOfferId()).build();
                }
            }
            """;

    private final DaoMethodExtractor extractor = new DaoMethodExtractor(new StoreTypeInferencer());

    @Test
    void extract_saveToDb_linksDomainAndEntity() {
        var files = List.of(
                javaFile("PartnerOfferCallRecorderDao.java", DAO_INTERFACE,
                        "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao"),
                javaFile("PartnerOfferCallRecorderDaoImpl.java", DAO_IMPL,
                        "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDaoImpl"));

        var entity = new FactBatch.DataObjectFact(
                "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity",
                "com.quotient.platform.domain.offer.PartnerOfferCallRecorder",
                "MARIADB", "PartnerOfferCallRecorder", "coupons_nextgen", "TABLE",
                "ENTITY_ANNOTATION", 0.95, null);

        List<FactBatch.AccessorMethodFact> facts = extractor.extract(files, List.of(entity));

        assertThat(facts).anyMatch(f ->
                "saveToDb".equals(f.methodName())
                        && "WRITE".equals(f.operation())
                        && "DAO".equals(f.accessorKind())
                        && "PartnerOfferCallRecorder".equals(f.physicalName())
                        && f.entityFqn() != null && f.entityFqn().contains("PartnerOfferCallRecorderEntity")
                        && f.domainFqn() != null && f.domainFqn().contains("PartnerOfferCallRecorder"));
    }

    @Test
    void extract_readMethods_classifiedAsRead() {
        var files = List.of(
                javaFile("PartnerOfferCallRecorderDao.java", DAO_INTERFACE,
                        "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao"),
                javaFile("PartnerOfferCallRecorderDaoImpl.java", DAO_IMPL,
                        "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDaoImpl"));

        List<FactBatch.AccessorMethodFact> facts = extractor.extract(files, List.of());

        assertThat(facts).anyMatch(f -> "isSubmitted".equals(f.methodName()) && "READ".equals(f.operation()));
        assertThat(facts).anyMatch(f -> "markAllPendingAsProcessed".equals(f.methodName()) && "WRITE".equals(f.operation()));
        assertThat(facts).allMatch(f -> f.storeType() != null);
        assertThat(facts).anyMatch(f -> "isSubmitted".equals(f.methodName()) && "MARIADB".equals(f.storeType()));
    }

    private static ProtoSchemaExtractor.JavaSourceFile javaFile(String path, String content, String fqn) {
        return new ProtoSchemaExtractor.JavaSourceFile(path, content, fqn);
    }
}
