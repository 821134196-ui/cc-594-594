import { writable } from 'svelte/store';

export const toast = writable(null);
let timer;

export function showToast(text, kind = 'ok') {
  toast.set({ text, kind });
  clearTimeout(timer);
  timer = setTimeout(() => toast.set(null), 4200);
}
