// Text fields on a TV: the field is a focusable box; OK opens the TV keyboard (real DOM focus on the <input>),
// OK/Done/Back on the keyboard closes it and D-pad navigation resumes.
import { h } from './dom.ts';

export interface FieldOpts { label: string; value?: string; type?: 'text' | 'password' | 'url'; placeholder?: string; onChange?: (v: string) => void }

export interface Field { el: HTMLElement; input: HTMLInputElement; get value(): string; set value(v: string) }

export function textField(o: FieldOpts): Field {
  const input = h('input', {
    type: o.type === 'password' ? 'password' : 'text', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false',
    placeholder: o.placeholder || '',
  });
  input.value = o.value || '';
  input.addEventListener('input', () => o.onChange && o.onChange(input.value));
  const el = h('label', { class: 'field s102', 'data-focus': true }, h('div', { class: 'flabel' }, o.label), input);
  el.addEventListener('click', (e) => { e.preventDefault(); startEditing(input); });
  return {
    el, input,
    get value() { return input.value; },
    set value(v: string) { input.value = v; },
  };
}

let editing: HTMLInputElement | null = null;

export function startEditing(input: HTMLInputElement): void {
  editing = input;
  input.focus();
  try { input.setSelectionRange(input.value.length, input.value.length); } catch { /* password fields */ }
}

export function isEditing(): boolean { return editing !== null && document.activeElement === editing; }

export function stopEditing(): void {
  if (editing) editing.blur();
  editing = null;
}

/** Keys while the TV keyboard is open. Returns true when the key belongs to the input. */
export function handleEditingKey(e: KeyboardEvent): boolean {
  if (!isEditing()) { editing = null; return false; }
  const c = e.keyCode;
  // 65376 = Done, 65385 = Cancel (Samsung IME), 13 Enter (also LG keyboard), 10009 / 461 Back (Samsung / LG),
  // 27 Esc; up/down leave the field.
  if (c === 65376 || c === 65385 || c === 13 || c === 10009 || c === 461 || c === 27 || c === 38 || c === 40) {
    stopEditing();
    return c !== 38 && c !== 40; // arrows also move the focus
  }
  return true;
}
