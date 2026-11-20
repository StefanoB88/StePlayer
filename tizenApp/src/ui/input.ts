// Text fields on a TV: the field is a focusable box; OK opens the TV keyboard (real DOM focus on the <input>),
// OK/Done/Back on the keyboard closes it and D-pad navigation resumes.
import { h, icon } from './dom.ts';

export interface FieldOpts {
  label: string; value?: string; type?: 'text' | 'password' | 'url'; placeholder?: string; onChange?: (v: string) => void;
  /** Search bar look (section search): magnifier, the label as placeholder, a × that empties it with the pointer. */
  search?: boolean;
}

export interface Field { el: HTMLElement; input: HTMLInputElement; get value(): string; set value(v: string) }

export function textField(o: FieldOpts): Field {
  const input = h('input', {
    type: o.type === 'password' ? 'password' : 'text', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false',
    placeholder: o.placeholder || (o.search ? o.label : ''),
  });
  input.value = o.value || '';
  const clearBtn = o.search ? h('span', { class: 'fclear' }, icon('close', 40)) : null;
  const sync = () => { if (clearBtn) clearBtn.style.display = input.value ? '' : 'none'; };
  input.addEventListener('input', () => { sync(); if (o.onChange) o.onChange(input.value); });
  const el = o.search
    ? h('label', { class: 'field search s102', 'data-focus': true }, icon('search', 40), input, clearBtn)
    : h('label', { class: 'field s102', 'data-focus': true }, h('div', { class: 'flabel' }, o.label), input);
  el.addEventListener('click', (e) => { e.preventDefault(); startEditing(input); });
  if (clearBtn) {
    // Pointer only (the remote clears with Back): empties the field without opening the keyboard.
    clearBtn.addEventListener('click', (e) => {
      e.preventDefault(); e.stopPropagation();
      input.value = ''; sync();
      if (o.onChange) o.onChange('');
    });
    // No :focus-within on Chromium 53/56: the border turns gold while the TV keyboard is open.
    input.addEventListener('focus', () => el.classList.add('editing'));
    input.addEventListener('blur', () => el.classList.remove('editing'));
  }
  sync();
  return {
    el, input,
    get value() { return input.value; },
    set value(v: string) { input.value = v; sync(); },
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
