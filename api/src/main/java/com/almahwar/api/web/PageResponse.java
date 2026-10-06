package com.almahwar.api.web;

import java.util.List;

/**
 * One page of a list. Every list endpoint is paged: {@code page} starts at 0, {@code size} is 20 by default and never
 * more than {@link PageQuery#MAX_SIZE}; the sort field is chosen from a per-endpoint allow-list.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalItems) {
        int pages = (int) ((totalItems + query.size() - 1) / query.size());
        return new PageResponse<>(items, query.page(), query.size(), totalItems, pages);
    }
}
