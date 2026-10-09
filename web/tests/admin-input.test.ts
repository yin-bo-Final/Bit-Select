import test from "node:test";
import assert from "node:assert/strict";
import {
  parseMoneyCents,
  parseReferencePrice,
  parseInventoryDelta,
  parseUserId,
} from "../src/lib/admin-input.ts";

test("admin currency preserves exact cents and rejects silent rounding or clamping", () => {
  assert.equal(parseMoneyCents("0.01", 1, 10_000_000), 1);
  assert.equal(parseMoneyCents("1.2", 1, 10_000_000), 120);
  assert.equal(parseMoneyCents("100000.00", 1, 10_000_000), 10_000_000);
  for (const value of [
    "",
    "0",
    "0.001",
    "1.005",
    "1.",
    "100000.01",
    "-1",
    "1e2",
    "Infinity",
    "NaN",
    " 2 ",
    "2,000",
    "9007199254740993",
    1.5,
    null,
  ]) {
    assert.equal(parseMoneyCents(value, 1, 10_000_000), null, String(value));
  }
});

test("optional reference prices reject invalid drafts instead of saving zero", () => {
  assert.equal(parseReferencePrice(undefined), 0);
  assert.equal(parseReferencePrice(""), 0);
  assert.equal(parseReferencePrice("0.00"), 0);
  assert.equal(parseReferencePrice("1000000.00"), 100_000_000);
  for (const value of [" ", "0.001", "1000000.01", "-1", "1e2", null])
    assert.equal(parseReferencePrice(value), null, String(value));
});

test("inventory deltas cannot silently round fractions or exceed available stock", () => {
  assert.equal(parseInventoryDelta("-7", 7), -7);
  assert.equal(parseInventoryDelta("1", 0), 1);
  assert.equal(parseInventoryDelta("100000", 7), 100_000);
  for (const value of [
    "",
    "0",
    "-0",
    "1.5",
    "-8",
    "100001",
    "1e2",
    " 2 ",
    "9007199254740993",
    2,
    null,
  ]) {
    assert.equal(parseInventoryDelta(value, 7), null, String(value));
  }
});

test("user filters require positive IDs without lost integer precision", () => {
  assert.equal(parseUserId("1"), 1);
  assert.equal(
    parseUserId(String(Number.MAX_SAFE_INTEGER)),
    Number.MAX_SAFE_INTEGER,
  );
  for (const value of [
    "",
    "0",
    "-1",
    "1.5",
    "1.",
    "1e2",
    " 2 ",
    "9007199254740992",
    "9007199254740993",
    2,
    null,
  ]) {
    assert.equal(parseUserId(value), null, String(value));
  }
});
