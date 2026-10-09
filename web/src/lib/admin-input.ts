/** Parse decimal currency exactly; never round a draft into another amount. */
export function parseMoneyCents(
  input: unknown,
  minCents: number,
  maxCents: number,
): number | null {
  if (typeof input !== "string" || !/^\d+(?:\.\d{1,2})?$/.test(input))
    return null;
  const [whole, fraction = ""] = input.split(".");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(cents) && cents >= minCents && cents <= maxCents
    ? cents
    : null;
}

/** An optional reference price may be empty, but an invalid draft is not zero. */
export function parseReferencePrice(input: unknown): number | null {
  if (input === undefined || input === "") return 0;
  return parseMoneyCents(input, 0, 100_000_000);
}

export function parseInventoryDelta(
  input: unknown,
  stock: number,
): number | null {
  if (typeof input !== "string" || !/^-?\d+$/.test(input)) return null;
  const value = Number(input);
  return Number.isSafeInteger(value) &&
    value !== 0 &&
    value >= -stock &&
    value <= 100_000
    ? value
    : null;
}

/** Keep user IDs within the range that JavaScript can represent exactly. */
export function parseUserId(input: unknown): number | null {
  if (typeof input !== "string" || !/^\d+$/.test(input)) return null;
  const value = Number(input);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}
