package io.testseer.backend.config.workspace;

public class DuplicateCatalogLibraryException extends RuntimeException {

    public DuplicateCatalogLibraryException(String orgId, String libraryId) {
        super("Catalog library already exists: orgId=" + orgId + ", libraryId=" + libraryId);
    }
}
