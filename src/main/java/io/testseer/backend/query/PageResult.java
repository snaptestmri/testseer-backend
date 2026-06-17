package io.testseer.backend.query;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
) {
    public static <T> PageResult<T> of(List<T> items, long total, int limit, int offset) {
        return new PageResult<>(items, total, limit, offset, (long) offset + items.size() < total);
    }
}
