import type { PageResult, Product } from "./types";

export type Category = { id: string; name: string; count: number };
export type HomeCatalog = {
  products: PageResult<Product> | null;
  categories: Category[];
};

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const nonnegativeInteger = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0;

function product(value: unknown): value is Product {
  return (
    record(value) &&
    nonnegativeInteger(value.id) &&
    value.id > 0 &&
    typeof value.name === "string" &&
    value.name.trim().length > 0 &&
    typeof value.category === "string" &&
    (value.categoryName === undefined ||
      typeof value.categoryName === "string") &&
    typeof value.description === "string" &&
    typeof value.imageUrl === "string" &&
    nonnegativeInteger(value.priceCents) &&
    nonnegativeInteger(value.stock) &&
    typeof value.featured === "boolean" &&
    typeof value.enabled === "boolean"
  );
}

function products(value: unknown): PageResult<Product> | null {
  if (
    !record(value) ||
    !Array.isArray(value.items) ||
    !value.items.every(product) ||
    !nonnegativeInteger(value.total) ||
    value.total < value.items.length
  )
    return null;
  return {
    items: value.items,
    total: value.total,
    page: 1,
    pageSize: 12,
  };
}

function categories(value: unknown): Category[] {
  if (
    !Array.isArray(value) ||
    !value.every(
      (item) =>
        record(item) &&
        typeof item.id === "string" &&
        item.id.length > 0 &&
        typeof item.name === "string" &&
        item.name.length > 0 &&
        nonnegativeInteger(item.count),
    )
  )
    return [];
  return value;
}

async function publicJson(url: string): Promise<unknown> {
  try {
    const response = await fetch(url, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(1500),
    });
    if (!response.ok) return null;
    const body: unknown = await response.json();
    return record(body) && "data" in body ? body.data : body;
  } catch {
    // API availability must not prevent the storefront or client retry mounting.
    return null;
  }
}

/** Public server snapshot only; credentials and private account data never enter it. */
export async function loadHomeCatalog(
  gateway = process.env.API_GATEWAY_URL || "http://127.0.0.1:8080",
): Promise<HomeCatalog> {
  const base = gateway.replace(/\/+$/, "");
  const [catalog, taxonomy] = await Promise.all([
    publicJson(`${base}/api/products?page=1&pageSize=12&sort=featured`),
    publicJson(`${base}/api/categories`),
  ]);
  return { products: products(catalog), categories: categories(taxonomy) };
}
