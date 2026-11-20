// Search bar of Live / Film / Serie (TV app screens/sectionSearch.ts): the core smart search (typo-tolerant)
// limited to that section. Debounced; from 2 characters the results replace the section's list, an empty field
// restores it, Back empties the field first.
import { useEffect, useState } from 'react';
import type { SmartSearchResults } from '../../../tizenApp/src/core/index.ts';
import { useSectionBack } from './nav';
import { useSession } from './session';

export function useSectionSearch() {
  const { core, source } = useSession();
  const id = source ? source.id : -1;
  const [text, setText] = useState('');
  const [results, setResults] = useState<SmartSearchResults | null>(null);
  const q = text.trim();
  const query = q.length >= 2 ? q : '';

  useEffect(() => {
    if (!query) { setResults(null); return undefined; } // the previous results stay while the next ones load
    let alive = true;
    const t = setTimeout(() => {
      core.search.search(id, query)
        .then((r) => { if (alive) setResults(r); })
        .catch(() => {
          if (alive) setResults({ query, correctedQuery: null, terms: [], topHits: [], liveNow: [], upcoming: [], replay: [], channels: [], movies: [], series: [] });
        });
    }, 300);
    return () => { alive = false; clearTimeout(t); };
  }, [core, id, query]);

  // Back clears the field. The focused result disappears with it, so the first item of the restored list takes
  // the focus (briefly marked "preferred"), otherwise nothing on screen would be focused.
  const [refocus, setRefocus] = useState(false);
  useEffect(() => {
    if (!refocus) return undefined;
    const t = setTimeout(() => setRefocus(false), 600);
    return () => clearTimeout(t);
  }, [refocus]);

  useEffect(() => { setText(''); }, [id]); // another list: start over
  useSectionBack(() => { if (!text) return false; setText(''); setRefocus(true); return true; }, [text]);

  return {
    text, setText,
    /** Query whose results replace the list ('' = normal list). */
    query,
    /** null while the first results load. */
    results: query ? results : null,
    /** True right after Back cleared the field: the first list item should take the focus. */
    refocus,
    /** A result is being opened: keep the query in the recent searches, like the global search. */
    remember: () => { if (query) core.search.rememberQuery(query).catch(() => undefined); },
  };
}
