import type { AuthSession } from "../types";

const STORAGE_KEY = "bank-demo-session";

/**
 * Where the signed-in session lives between page loads.
 *
 * Deliberately owned here rather than inside AuthContext: the API client needs the
 * token to set an Authorization header, and React context isn't reachable from a
 * plain module. Both read this one place, so a page refresh can't leave the client
 * sending requests with a token the context has already discarded.
 */
export function loadSession(): AuthSession | null {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (!stored) return null;
  try {
    return JSON.parse(stored) as AuthSession;
  } catch {
    // Corrupted or from an older format — treat as signed out rather than crashing on boot.
    localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function saveSession(session: AuthSession): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  localStorage.removeItem(STORAGE_KEY);
}
