package io.testseer.backend.ingestion.catalog;

public enum StoreType {
    MARIADB,
    CASSANDRA,
    MONGODB,
    BIGQUERY,
    UNKNOWN;

    public String dbValue() {
        return name();
    }

    public static StoreType fromPackageHint(String classFqn) {
        if (classFqn == null) return UNKNOWN;
        if (classFqn.contains(".data.mongo.")) return MONGODB;
        if (classFqn.contains(".data.nosql.")) return CASSANDRA;
        if (classFqn.contains(".data.rdb.") || classFqn.contains(".data.mariadb.")) return MARIADB;
        return UNKNOWN;
    }
}
