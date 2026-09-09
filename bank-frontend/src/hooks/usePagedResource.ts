import { useCallback, useEffect, useState } from "react";
import type { Page } from "../types";

/** A fetcher for one page of a resource. */
export type PageFetcher<T> = (page: number, size: number) => Promise<Page<T>>;

export interface PagedResource<T> {
  items: T[];
  page: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  loading: boolean;
  setPage: (page: number) => void;
  /** Re-reads the current page — used after something changes the underlying data. */
  refresh: () => void;
}

/**
 * Page state and loading for one paged list.
 *
 * Extracted because five tables need exactly this and copying it into each is how they end
 * up subtly different — one forgetting to reset to the first page, another leaving a stale
 * page number pointing past the end of a shrunken list.
 *
 * A refresh that empties the current page steps back rather than showing an empty table:
 * deleting the last row of page three should show page two, not nothing.
 */
export function usePagedResource<T>(fetcher: PageFetcher<T>, size = 10): PagedResource<T> {
  const [items, setItems] = useState<T[]>([]);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(false);

  const load = useCallback(
    (requestedPage: number) => {
      setLoading(true);
      fetcher(requestedPage, size)
        .then((result) => {
          // Landed past the end — step back to the last page that has rows.
          if (result.content.length === 0 && result.totalPages > 0 && requestedPage >= result.totalPages) {
            setPage(result.totalPages - 1);
            return;
          }
          setItems(result.content);
          setTotalElements(result.totalElements);
          setTotalPages(result.totalPages);
          setHasNext(result.hasNext);
        })
        .catch(() => {
          // Left to the caller's own error handling: a table that cannot load shows empty
          // rather than a stale page from a different account.
          setItems([]);
        })
        .finally(() => setLoading(false));
    },
    [fetcher, size]
  );

  useEffect(() => {
    load(page);
  }, [load, page]);

  return {
    items,
    page,
    totalElements,
    totalPages,
    hasNext,
    loading,
    setPage,
    refresh: () => load(page),
  };
}
