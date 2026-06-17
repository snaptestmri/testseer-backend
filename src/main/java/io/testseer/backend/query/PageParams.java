package io.testseer.backend.query;

public final class PageParams {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private PageParams() {}

    public record Validated(int limit, int offset) {}

    public static Validated validate(Integer limit, Integer offset) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (resolvedLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (resolvedLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be <= " + MAX_LIMIT);
        }
        int resolvedOffset = offset == null ? 0 : offset;
        if (resolvedOffset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        return new Validated(resolvedLimit, resolvedOffset);
    }
}
