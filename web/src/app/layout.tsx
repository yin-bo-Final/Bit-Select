import type { Metadata } from "next";
import localFont from "next/font/local";
import { AntdRegistry } from "@ant-design/nextjs-registry";
import { Providers } from "@/components/providers";
import { SiteShell } from "@/components/site-shell";
import "antd/dist/reset.css";
import "./globals.css";
import "./design-system.css";
import "./storefront.css";
import "./commerce.css";
import "./workspace.css";

const manrope = localFont({
  src: "./fonts/Manrope.ttf",
  variable: "--font-display",
  display: "swap",
  weight: "200 800",
});

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
      <body className={manrope.variable}>
        <script
          dangerouslySetInnerHTML={{
            __html: `try{var p=localStorage.getItem('bit-select-theme');document.documentElement.dataset.theme=p==='dark'||(!p&&matchMedia('(prefers-color-scheme: dark)').matches)?'dark':'light'}catch{}`,
          }}
        />
        <AntdRegistry>
          <Providers>
            <SiteShell>{children}</SiteShell>
          </Providers>
        </AntdRegistry>
      </body>
    </html>
  );
}
