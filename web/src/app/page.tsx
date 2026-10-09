import { StorefrontHome } from "@/components/storefront-home";
import { loadHomeCatalog } from "@/lib/home-catalog";

export const dynamic = "force-dynamic";

export default async function Home() {
  const initial = await loadHomeCatalog();
  return <StorefrontHome initial={initial} />;
}
