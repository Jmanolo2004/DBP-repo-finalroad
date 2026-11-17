package com.fidegresa.common;

import java.util.List;
import java.util.function.Function;

/** Página con la misma forma que Spring Data: content, totalElements, totalPages, number, size, first, last. */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int number, int size, boolean first, boolean last) {

    /** Pagina una lista ya filtrada y ordenada. size se limita a 1–100. */
    public static <T> PageResponse<T> of(List<T> items, Integer page, Integer size) {
        int pageSize = Math.min(100, Math.max(1, size == null ? 10 : size));
        int totalPages = Math.max(1, (int) Math.ceil(items.size() / (double) pageSize));
        int number = Math.min(Math.max(0, page == null ? 0 : page), totalPages - 1);
        int from = Math.min(items.size(), number * pageSize);
        int to = Math.min(items.size(), from + pageSize);
        return new PageResponse<>(List.copyOf(items.subList(from, to)), items.size(), totalPages, number, pageSize, number == 0, number >= totalPages - 1);
    }

    public static <S, T> PageResponse<T> from(org.springframework.data.domain.Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getTotalElements(),
                Math.max(1, page.getTotalPages()), page.getNumber(), page.getSize(), page.isFirst(), page.isLast() || page.getTotalPages() == 0);
    }
}
