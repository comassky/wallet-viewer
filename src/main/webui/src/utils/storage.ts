/** localStorage is optional: private-browsing and blocked storage must never throw, only degrade. */
export function readStorage(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}

export function writeStorage(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* Preferences still work when storage is blocked. */ }
}
