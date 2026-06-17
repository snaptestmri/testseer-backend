package io.testseer.backend.ingestion.catalog;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class StoreTypeInferencer {

    public StoreType inferFromEntity(String classFqn, List<String> annotations, String content) {
        if (annotations.contains("Document")) {
            return StoreType.MONGODB;
        }
        if (annotations.contains("Entity")) {
            StoreType pkg = StoreType.fromPackageHint(classFqn);
            return pkg != StoreType.UNKNOWN ? pkg : StoreType.MARIADB;
        }
        if (content != null && content.contains("@Table") && classFqn != null
                && classFqn.contains(".nosql.")) {
            return StoreType.CASSANDRA;
        }
        StoreType pkg = StoreType.fromPackageHint(classFqn);
        return pkg != StoreType.UNKNOWN ? pkg : StoreType.UNKNOWN;
    }

    public StoreType inferFromRepoInterface(String repoFqn, String extendsClause) {
        if (extendsClause == null) return StoreType.UNKNOWN;
        String lower = extendsClause.toLowerCase(Locale.ROOT);
        if (lower.contains("mongorepository")) return StoreType.MONGODB;
        if (lower.contains("basenosqlrepository") || lower.contains("cassandrarepository")) {
            return StoreType.CASSANDRA;
        }
        if (lower.contains("jparepository")) return StoreType.MARIADB;
        return StoreType.fromPackageHint(repoFqn);
    }
}
