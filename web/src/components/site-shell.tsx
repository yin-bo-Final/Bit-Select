"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { App, Button, Dropdown, Tooltip } from "antd";
import {
  ChatCircleDots,
  ShoppingBag,
  Moon,
  Sun,
  UserCircle,
  List,
  ArrowUpRight,
  Pause,
  Play,
} from "@phosphor-icons/react";
import { useSession } from "./providers";
import { errorText } from "@/lib/api";
import { useEffect, useRef } from "react";
import { AmbientField } from "./ambient-field";
import { useLoginHref } from "@/lib/use-login-href";

const links = [
  { href: "/", label: "精选好物" },
  { href: "/assistant", label: "导购助手" },
  { href: "/orders", label: "我的订单" },
];
export function SiteShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const loginDestination = useLoginHref();
  const router = useRouter();
  const { user, logout, dark, toggleTheme, effectsEnabled, toggleEffects } =
    useSession();
  const { message } = App.useApp();
  const contentRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (
      !effectsEnabled ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    )
      return;
    const animation = contentRef.current?.animate(
      [
        { opacity: 0.65, transform: "translateY(8px)" },
        { opacity: 1, transform: "translateY(0)" },
      ],
      { duration: 320, easing: "cubic-bezier(.22,1,.36,1)" },
    );
    return () => animation?.cancel();
  }, [path, effectsEnabled]);
  return (
    <div className="site-frame">
      <AmbientField />
      <a href="#main-content" className="skip-link">
        跳至主要内容
      </a>
      <div className="announcement">
        用心挑选，让日常更好一点。<span>电子科技 / 居家生活</span>
      </div>
      <header className="site-header">
        <div className="header-inner">
          <Link
            href="/"
            className="brand"
            aria-label="b. 比特严选 BIT SELECT 首页"
          >
            <span className="brand-mark">b.</span>
            <span>
              比特严选<small>BIT SELECT</small>
            </span>
          </Link>
          <nav aria-label="主导航" className="desktop-nav">
            {links.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                aria-current={path === link.href ? "page" : undefined}
              >
                {link.label}
              </Link>
            ))}
          </nav>
          <div className="header-actions">
            <Tooltip title={effectsEnabled ? "暂停背景动效" : "开启背景动效"}>
              <Button
                className="effects-toggle"
                type="text"
                shape="circle"
                aria-label={effectsEnabled ? "暂停背景动效" : "开启背景动效"}
                aria-pressed={effectsEnabled}
                icon={effectsEnabled ? <Pause size={18} /> : <Play size={18} />}
                onClick={toggleEffects}
              />
            </Tooltip>
            <Button
              type="text"
              shape="circle"
              aria-label={dark ? "切换浅色外观" : "切换深色外观"}
              icon={dark ? <Sun size={21} /> : <Moon size={21} />}
              onClick={toggleTheme}
            />
            <Link href="/cart" aria-label="购物袋" className="icon-link">
              <ShoppingBag size={23} />
            </Link>
            {user ? (
              <Dropdown
                trigger={["click"]}
                menu={{
                  items: [
                    {
                      key: "account",
                      label: "我的钱包与账户",
                      onClick: () => router.push("/account"),
                    },
                    ...(user.role === "ADMIN"
                      ? [
                          {
                            key: "admin",
                            label: "管理后台",
                            onClick: () => router.push("/admin"),
                          },
                          {
                            key: "operations",
                            label: "运维工作台",
                            onClick: () => router.push("/admin/ops"),
                          },
                        ]
                      : []),
                    {
                      key: "logout",
                      label: "退出登录",
                      onClick: () => {
                        void logout()
                          .then(() => router.push("/"))
                          .catch((e) => message.error(errorText(e)));
                      },
                    },
                  ],
                }}
              >
                <Button
                  type="text"
                  className="account-button"
                  aria-label={`账户：${user.nickname || user.username}`}
                  icon={<UserCircle size={22} />}
                >
                  {user.nickname || user.username}
                </Button>
              </Dropdown>
            ) : (
              <Link
                href={loginDestination}
                className="login-link"
                aria-label="登录 / 注册"
              >
                <UserCircle size={22} />
                <span>登录 / 注册</span>
              </Link>
            )}
            <Dropdown
              trigger={["click"]}
              menu={{
                items: [
                  ...links.map((link) => ({
                    key: link.href,
                    label: link.label,
                    onClick: () => router.push(link.href),
                  })),
                  {
                    key: "/account",
                    label: "我的钱包",
                    onClick: () => router.push("/account"),
                  },
                  ...(user?.role === "ADMIN"
                    ? [
                        {
                          key: "/admin/ops",
                          label: "运维工作台",
                          onClick: () => router.push("/admin/ops"),
                        },
                      ]
                    : []),
                ],
              }}
            >
              <Button
                className="mobile-menu"
                type="text"
                shape="circle"
                aria-label="展开导航"
                icon={<List size={23} />}
              />
            </Dropdown>
          </div>
        </div>
      </header>
      <main
        id="main-content"
        ref={contentRef}
        tabIndex={-1}
        className={path === "/assistant" ? "chat-main" : "main-content"}
      >
        {children}
      </main>
      {path !== "/assistant" && (
        <footer className="site-footer">
          <div>
            <Link href="/" className="footer-brand">
              比特严选
            </Link>
            <span className="footer-wordmark" aria-hidden="true">
              BIT SELECT
            </span>
            <p>少一点选择负担，多一点日常喜欢。</p>
          </div>
          <div className="footer-links">
            <Link href="/assistant">
              让导购帮你选 <ArrowUpRight size={16} />
            </Link>
            <Link href="/account">账户与钱包</Link>
            <span>演示商城 · 使用平台余额交易</span>
          </div>
        </footer>
      )}
      {path === "/orders" && (
        <Link href="/assistant" className="assistant-fab">
          <ChatCircleDots size={22} />
          <span>帮我选</span>
        </Link>
      )}
    </div>
  );
}
