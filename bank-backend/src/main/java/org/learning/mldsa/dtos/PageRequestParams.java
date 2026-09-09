package org.learning.mldsa.dtos;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Turns the {@code page} and {@code size} query parameters into a Pageable.
 *
 * The cap matters: without one, a caller asking for a page of a million rows would make the
 * server assemble it, which turns pagination from a protection into an instruction to load
 * everything anyway. Values outside the allowed range are clamped rather than rejected,
 * since a client asking for too much wants as much as it can have.
 */
public final class PageRequestParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestParams() {
    }

    public static Pageable of(Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int requestedSize = size == null ? DEFAULT_SIZE : size;
        int safeSize = Math.clamp(requestedSize, 1, MAX_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
