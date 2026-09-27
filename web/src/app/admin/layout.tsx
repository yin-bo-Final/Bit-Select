import type { Metadata } from "next";
export const metadata: Metadata = {
  title: "商城管理",
  robots: { index: false, follow: false },
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return children;
}
