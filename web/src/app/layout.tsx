import type { Metadata } from "next";
import { AntdRegistry } from "@ant-design/nextjs-registry";
import { Providers } from "@/components/providers";
import { SiteShell } from "@/components/site-shell";
import "antd/dist/reset.css";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "比特严选 · 好好选，慢慢用", template: "%s | 比特严选" },
  description:
    "精选电子产品与日常生活用品。让懂你的导购助手，帮你找到合适的那一件。",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN" data-scroll-behavior="smooth" suppressHydrationWarning>
      <body>
        <AntdRegistry>
          <Providers>
            <SiteShell>{children}</SiteShell>
          </Providers>
        </AntdRegistry>
      </body>
    </html>
  );
}
