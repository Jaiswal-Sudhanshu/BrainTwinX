package com.braintwinx.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A page of results.
 *
 * <p>Exists so Spring Data's {@code Page} is never serialised directly. {@code Page} has an
 * unstable JSON shape across versions and exposes internals such as {@code pageable} and
 * {@code sort} that are not part of this API's contract. Mapping to an explicit record keeps the
 * response shape owned by this codebase (brief section 18).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
