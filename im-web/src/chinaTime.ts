const chinaTimeZone = "Asia/Shanghai";

// MySQL TIMESTAMP values are read with a UTC database session. Java's
// LocalDateTime JSON has no offset, so interpret those strings as UTC.
// Explicit offsets and Z from other sources already identify an instant.
export function parseServerTime(value: string): Date {
  return new Date(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value) ? value : `${value}Z`);
}

export function chinaMessageTime(value: string): string {
  return parseServerTime(value).toLocaleTimeString("zh-CN", {
    timeZone: chinaTimeZone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
}

export function chinaDateTime(value: string): string {
  return parseServerTime(value).toLocaleString("zh-CN", { timeZone: chinaTimeZone });
}

export function chinaDate(value: string): string {
  return parseServerTime(value).toLocaleDateString("zh-CN", { timeZone: chinaTimeZone });
}

export function chinaToday(now: Date = new Date()): string {
  const fields = new Intl.DateTimeFormat("en-US", {
    timeZone: chinaTimeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const part = (name: string) => fields.find((field) => field.type === name)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}
