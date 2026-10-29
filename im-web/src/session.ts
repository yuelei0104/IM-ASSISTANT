export const sessionKey = "im-assistant-server-token-v1";

export function sessionToken(): string | null {
  try { return sessionStorage.getItem(sessionKey); }
  catch { return null; }
}

export function clearSession(): void {
  try { sessionStorage.removeItem(sessionKey); }
  catch { /* Storage may be blocked by the browser. */ }
}

const expiredListeners = new Set<() => void>();
export function onSessionExpired(listener: () => void): () => void {
  expiredListeners.add(listener);
  return () => expiredListeners.delete(listener);
}

/** Ignore a late 401 for a token that was replaced by a newer login. */
export function expireIfCurrent(token: string): void {
  if (!token || sessionToken() !== token) return;
  clearSession();
  for (const listener of expiredListeners) listener();
}
