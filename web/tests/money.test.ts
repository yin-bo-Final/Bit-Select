import test from "node:test";
import assert from "node:assert/strict";
import { money } from "../src/lib/api.ts";
test("cent-denominated API amounts display in yuan for prices, payments and refunds", () => {
  assert.equal(money(9900), "¥99.00");
  assert.equal(money(1), "¥0.01");
  assert.equal(money(-3900), "-¥39.00");
  assert.equal(money(50000), "¥500.00");
});
