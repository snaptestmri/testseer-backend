package io.testseer.backend.config.workspace;

public class CatalogLibraryNotFoundException extends RuntimeException {

    public CatalogLibraryNotFoundException(String orgId, String libraryId) {
        super("Catalog library not found: orgId=" + orgId + ", libraryId=" + libraryId);
    }
}
