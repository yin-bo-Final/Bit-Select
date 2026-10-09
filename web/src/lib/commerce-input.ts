/** Whole physical products only; the server also limits an order line to 99. */
export function isPurchaseQuantity(
  value: unknown,
  stock: number,
): value is number {
  return (
    typeof value === "number" &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= Math.min(99, stock)
  );
}

/** Do not silently round, truncate or interpret a partially typed quantity. */
export function parsePurchaseQuantity(
  input: string,
  stock: number,
): number | null {
  if (!/^\d+$/.test(input)) return null;
  const value = Number(input);
  return isPurchaseQuantity(value, stock) ? value : null;
}

export function isValidPhone(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const phone = value.trim();
  return (
    phone.length >= 6 &&
    phone.length <= 20 &&
    /^\+?\d[\d -]*$/.test(phone) &&
    (phone.match(/\d/g)?.length || 0) >= 6
  );
}

/** A valid draft still cannot be checked out until its server save succeeds. */
export function isCartQuantitySynced(
  draft: string | undefined,
  quantity: number,
  stock: number,
): boolean {
  return (
    isPurchaseQuantity(quantity, stock) &&
    (draft === undefined || parsePurchaseQuantity(draft, stock) === quantity)
  );
}

export async function validatePhone(_: unknown, value: unknown): Promise<void> {
  if (!isValidPhone(value)) {
    throw new Error(
      "请输入至少 6 位数字的联系电话，可使用空格、短横线或开头的 +",
    );
  }
}
