// Search bar of the Live / Film / Serie tabs: the core smart search (typo-tolerant) limited to that section.
// Typing is debounced; from 2 characters the tab shows the results instead of its list, clearing restores it.
import { app } from '../app.ts';
import { textField } from '../input.ts';

/** Same wording as the global search. */
export function noResultsText(q: string): string {
  return `Nessun risultato per «${q}». Prova con un'altra parola o controlla l'ortografia.`;
}

export interface SectionSearch {
  el: HTMLElement;
  /** Trimmed query (≥ 2 characters) whose results replace the list; '' when the normal list is shown. */
  readonly query: string;
  /** Something is typed, even a single character. */
  readonly hasText: boolean;
  /** Empties the field without notifying; true when results were shown (the caller restores its list). */
  reset(): boolean;
  /** A result is being opened: keep the query in the recent searches, like the global search. */
  remember(): void;
}

export function sectionSearch(label: string, onQuery: (q: string) => void): SectionSearch {
  let query = '';
  let timer = 0;
  const apply = (v: string) => {
    const q = v.trim().length >= 2 ? v.trim() : '';
    if (q === query) return;
    query = q;
    onQuery(q);
  };
  const field = textField({
    label, search: true,
    onChange: (v) => { window.clearTimeout(timer); timer = window.setTimeout(() => apply(v), 300); },
  });
  return {
    el: field.el,
    get query() { return query; },
    get hasText() { return field.value.length > 0; },
    reset() {
      window.clearTimeout(timer);
      const had = query !== '';
      field.value = '';
      query = '';
      return had;
    },
    remember() { if (query) app().core.search.rememberQuery(query).catch(() => undefined); },
  };
}
