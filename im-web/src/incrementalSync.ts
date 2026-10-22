type MessageWithId = { id: number };
type Page<T> = { messages: T[]; nextAfterId: number | null };

// The cursor advances only after a page has been applied. A failed request resumes there.
export async function syncForward<T extends MessageWithId>(
  afterId: number,
  fetchPage: (cursor: number) => Promise<Page<T>>,
  applyPage: (messages: T[], cursor: number) => boolean,
): Promise<number | null> {
  let cursor = afterId;
  while (true) {
    const page = await fetchPage(cursor);
    let previous = cursor;
    for (const message of page.messages) {
      if (!Number.isSafeInteger(message.id) || message.id <= previous)
        throw new Error("增量消息顺序无效");
      previous = message.id;
    }
    if (page.nextAfterId !== null && (previous === cursor || page.nextAfterId !== previous))
      throw new Error("增量消息游标无效");
    if (!applyPage(page.messages, previous)) return null;
    cursor = previous;
    if (page.nextAfterId === null) return cursor;
  }
}
