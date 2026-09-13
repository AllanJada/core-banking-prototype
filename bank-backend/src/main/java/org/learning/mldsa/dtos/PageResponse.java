package org.learning.mldsa.dtos;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of results, in the shape every paged endpoint returns.
 *
 * Deliberately this project's own type rather than Spring's {@code Page} serialized
 * directly: that class's JSON form carries its internal pageable and sort objects, is
 * documented as unstable across versions, and would tie the API's contract to a framework
 * detail. This carries only what a client actually needs to render a pager.
 *
 * {@code hasNext} is included even though it is derivable from page and totalPages, because
 * "is there more" is the question a client asks on every render and computing it in three
 * different places is how the three end up disagreeing.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    /** Wraps a page of entities, mapping each one to its response form. */
    public static <E, R> PageResponse<R> of(Page<E> source, Function<E, R> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext()
        );
    }
}
