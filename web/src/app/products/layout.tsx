import type { Metadata } from "next";
export const metadata: Metadata = { title: "商品详情" };
export default function Layout({ children }: { children: React.ReactNode }) {
  return children;
}
