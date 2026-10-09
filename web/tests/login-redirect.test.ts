import test from "node:test";
import assert from "node:assert/strict";
import { loginHref, resolveLoginReturnPath } from "../src/lib/login-redirect";

test("login restores a local page and its product question or fragment", () => {
  for (const target of [
    "/cart",
    "/orders",
    "/products/76",
    "/assistant?product=76&name=%E8%80%B3%E6%9C%BA#input",
  ])
    assert.equal(resolveLoginReturnPath(target), target);
  assert.equal(
    new URL(
      loginHref("/assistant?product=76&name=耳机"),
      "https://bit-select.invalid",
    ).searchParams.get("next"),
    "/assistant?product=76&name=%E8%80%B3%E6%9C%BA",
  );
});

test("login never follows an external or malformed return URL", () => {
  for (const target of [
    null,
    "",
    "https://evil.example",
    "//evil.example",
    "/\\evil.example",
    "/%2f%2fevil.example",
    "/%5cevil.example",
    "/%0aevil",
    "/bad%ZZ",
    "javascript:alert(1)",
  ])
    assert.equal(resolveLoginReturnPath(target), "/");
});

test("login rejects loops, including normalized and encoded login paths", () => {
  for (const target of [
    "/login",
    "/login?next=/cart",
    "/login/",
    "/cart/../login",
    "/%6cogin",
  ])
    assert.equal(resolveLoginReturnPath(target), "/");
});
