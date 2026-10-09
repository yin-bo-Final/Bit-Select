import test from "node:test";
import assert from "node:assert/strict";
import {
  isPurchaseQuantity,
  parsePurchaseQuantity,
  isCartQuantitySynced,
  isValidPhone,
  validatePhone,
} from "../src/lib/commerce-input.ts";

test("physical product quantities reject fractional, missing and unavailable stock", () => {
  assert.equal(isPurchaseQuantity(1, 1), true);
  assert.equal(isPurchaseQuantity(99, 200), true);
  for (const value of [null, undefined, "2", 0, -1, 1.5, 100, NaN, Infinity]) {
    assert.equal(isPurchaseQuantity(value, 200), false);
  }
  assert.equal(isPurchaseQuantity(2, 1), false);
  assert.equal(isPurchaseQuantity(1, 0), false);
});

test("checkout requires the displayed quantity to match the successful cart snapshot", () => {
  assert.equal(isCartQuantitySynced(undefined, 1, 10), true);
  assert.equal(isCartQuantitySynced("2", 2, 10), true);
  assert.equal(
    isCartQuantitySynced("2", 1, 10),
    false,
    "failed save must not buy the previous quantity",
  );
  assert.equal(isCartQuantitySynced("1.5", 1, 10), false);
  assert.equal(isCartQuantitySynced("", 1, 10), false);
  assert.equal(isCartQuantitySynced(undefined, 1, 0), false);
});

test("typed quantities cannot buy with truncated decimals or a prior valid draft", () => {
  assert.equal(parsePurchaseQuantity("2", 8), 2);
  for (const draft of [
    "",
    "1.5",
    "1.",
    "1e1",
    "-1",
    "0",
    "9",
    "Infinity",
    " 2 ",
  ]) {
    assert.equal(parsePurchaseQuantity(draft, 8), null, draft);
  }
  assert.equal(parsePurchaseQuantity("100", 200), null);
});

test("delivery contacts accept digits with separators but reject whitespace and symbols", async () => {
  for (const value of [
    "13800138000",
    "+86 138-0013-8000",
    "010-12345678",
    " 123456 ",
  ]) {
    assert.equal(isValidPhone(value), true, value);
    await validatePhone(null, value);
  }
  for (const value of [
    "",
    "      ",
    "------",
    "++++++",
    "12345",
    "12+3456",
    "123\t456",
    "123456789012345678901",
    null,
  ]) {
    assert.equal(isValidPhone(value), false, String(value));
    await assert.rejects(() => validatePhone(null, value), /至少 6 位数字/);
  }
});
