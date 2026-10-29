/**
 * Conversation ids carry their server id so a view can be routed without a lookup table.
 * Both parsers are strict: a malformed id must never silently become 0 or NaN.
 */
export function directPeer(id: string): number | null {
  if (!/^server-user-[1-9]\d*$/.test(id)) return null;
  const peerId = Number(id.slice(12));
  return Number.isSafeInteger(peerId) ? peerId : null;
}

export function serverGroupId(id: string): number | null {
  if (!/^server-group-[1-9]\d*$/.test(id)) return null;
  const groupId = Number(id.slice(13));
  return Number.isSafeInteger(groupId) ? groupId : null;
}
