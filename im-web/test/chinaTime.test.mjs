import test from "node:test";
import assert from "node:assert/strict";
import { chinaDateTime, chinaMessageTime, chinaToday, parseServerTime } from "../src/chinaTime.ts";

test("UTC database timestamps display at China Standard Time", () => {
  assert.equal(chinaMessageTime("2026-10-27T11:08:00"), "19:08");
  assert.equal(chinaMessageTime("2026-10-27T11:08:00Z"), "19:08");
  assert.equal(chinaMessageTime("2026-10-27T19:08:00+08:00"), "19:08");
  assert.equal(parseServerTime("2026-10-27T11:08:00").getTime(),
    Date.parse("2026-10-27T11:08:00Z"));
  assert.match(chinaDateTime("2026-10-27T11:08:00"), /19:08:00/);
});

test("overdue day boundary follows China Standard Time", () => {
  assert.equal(chinaToday(new Date("2026-10-27T16:30:00Z")), "2026-10-28");
});
