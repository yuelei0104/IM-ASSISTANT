type ConversationKind = "direct" | "group";
type CursorStore = Pick<Storage, "getItem" | "setItem">;

function key(accountId: number, kind: ConversationKind, conversationId: number): string {
  return `im-assistant:sync:v1:${accountId}:${kind}:${conversationId}`;
}

function stores(): CursorStore[] {
  const available: CursorStore[] = [];
  try { available.push(sessionStorage); } catch { /* Storage can be blocked. */ }
  try { available.push(localStorage); } catch { /* Storage can be blocked. */ }
  return available;
}

export function savedCursor(accountId: number, kind: ConversationKind, conversationId: number): number | null {
  const cursors: number[] = [];
  for (const storage of stores()) {
    try {
      const value = storage.getItem(key(accountId, kind, conversationId));
      if (value && /^(0|[1-9]\d*)$/.test(value)) {
        const parsed = Number(value);
        if (Number.isSafeInteger(parsed)) cursors.push(parsed);
      }
    } catch { /* Use the other store or the latest history page. */ }
  }
  // A second tab may have moved the shared cursor forward. Prefer the older
  // tab's cursor to avoid skipping messages that it has not applied.
  return cursors.length ? Math.min(...cursors) : null;
}

export function saveCursor(accountId: number, kind: ConversationKind,
                           conversationId: number, cursor: number): void {
  if (!Number.isSafeInteger(cursor) || cursor < 0) return;
  for (const storage of stores()) {
    try { storage.setItem(key(accountId, kind, conversationId), String(cursor)); }
    catch { /* In-memory recovery continues if storage is unavailable. */ }
  }
}

export function resumeCursor(saved: number | null, newest: number): number {
  // A server-side reset can invalidate a previously saved auto-increment ID.
  return saved !== null && saved <= newest ? saved : newest;
}
