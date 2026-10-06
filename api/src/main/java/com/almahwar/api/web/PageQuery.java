package com.almahwar.api.web;

/**
 * Validated paging input. Controllers declare {@code page} ({@value #MAX_PAGE} at most) and {@code size}
 * (1..{@value #MAX_SIZE}, default {@value #DEFAULT_SIZE}) with Bean Validation; this type carries them to the
 * repository, which turns them into {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} parameters (never concatenated).
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    public PageQuery {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("page/size out of range");
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
