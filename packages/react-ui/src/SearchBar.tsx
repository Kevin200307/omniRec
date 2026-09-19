import { useCallback, useRef, useState } from "react";
import { useOmnirec } from "@omnirec/react";
import { omnirecFetch } from "./api";

export interface SearchHit {
  id: string;
  title: string;
  imageUrl?: string;
  price?: number;
  [key: string]: unknown;
}

interface SearchResponse {
  hits: SearchHit[];
}

export interface SearchBarProps {
  placeholder?: string;
  debounceMs?: number;
  onResults?: (hits: SearchHit[]) => void;
  renderHit?: (hit: SearchHit) => React.ReactNode;
  className?: string;
}

/**
 * Calls the backend's /v1/search — never a provider SDK directly. Which
 * provider actually serves the query (or nothing, if no search provider is
 * configured) is a backend config decision this component doesn't know
 * about and doesn't need to.
 */
export function SearchBar({ placeholder = "Search products…", debounceMs = 200, onResults, renderHit, className }: SearchBarProps) {
  const { endpoint, tenantId, track } = useOmnirec();
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[]>([]);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const runSearch = useCallback(
    async (q: string) => {
      if (!q.trim()) {
        setHits([]);
        onResults?.([]);
        return;
      }
      try {
        const result = await omnirecFetch<SearchResponse>(endpoint, "/v1/search", { tenantId, q });
        setHits(result.hits);
        onResults?.(result.hits);
        track("SEARCH_QUERY", "EXPLICIT", { query: q, resultCount: result.hits.length });
      } catch {
        // Swallow — a failed search shouldn't crash the page; the input
        // just shows no results this keystroke.
      }
    },
    [endpoint, tenantId, track, onResults]
  );

  const onChange = (value: string) => {
    setQuery(value);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runSearch(value), debounceMs);
  };

  return (
    <div className={className}>
      <input type="search" value={query} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      {hits.length > 0 && (
        <ul>
          {hits.map((hit) => (
            <li key={hit.id}>{renderHit ? renderHit(hit) : hit.title}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
