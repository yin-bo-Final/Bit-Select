"use client";
import { App, ConfigProvider, theme } from "antd";
import zhCN from "antd/locale/zh_CN";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { api, post } from "@/lib/api";
import type { User } from "@/lib/types";

type Session = {
  user: User | null;
  loading: boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
  dark: boolean;
  toggleTheme: () => void;
};
const SessionContext = createContext<Session>({
  user: null,
  loading: true,
  refresh: async () => {},
  logout: async () => {},
  dark: false,
  toggleTheme: () => {},
});
export const useSession = () => useContext(SessionContext);
export function Providers({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [dark, setDark] = useState(false);
  const [themeReady, setThemeReady] = useState(false);
  const sessionRevision = useRef(0);
  const sessionRequest = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    const revision = ++sessionRevision.current;
    sessionRequest.current?.abort();
    const controller = new AbortController();
    sessionRequest.current = controller;
    try {
      const result = await api<User>("/auth/me", { signal: controller.signal });
      if (revision === sessionRevision.current) setUser(result);
    } catch {
      if (revision === sessionRevision.current) setUser(null);
    } finally {
      if (revision === sessionRevision.current) {
        sessionRequest.current = null;
        setLoading(false);
      }
    }
  }, []);
  useEffect(() => {
    void refresh();
    return () => {
      ++sessionRevision.current;
      sessionRequest.current?.abort();
    };
  }, [refresh]);
  useEffect(() => {
    let preference: string | null = null;
    try {
      preference = localStorage.getItem("bit-select-theme");
    } catch {}
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    setDark(preference ? preference === "dark" : media.matches);
    setThemeReady(true);
    const listener = (e: MediaQueryListEvent) => {
      try {
        if (!localStorage.getItem("bit-select-theme")) setDark(e.matches);
      } catch {
        setDark(e.matches);
      }
    };
    media.addEventListener("change", listener);
    return () => media.removeEventListener("change", listener);
  }, []);
  useEffect(() => {
    if (themeReady)
      document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [dark, themeReady]);
  const toggleTheme = () =>
    setDark((value) => {
      try {
        localStorage.setItem("bit-select-theme", value ? "light" : "dark");
      } catch {}
      return !value;
    });
  const logout = async () => {
    ++sessionRevision.current;
    sessionRequest.current?.abort();
    await post("/auth/logout");
    ++sessionRevision.current;
    sessionRequest.current?.abort();
    setUser(null);
    setLoading(false);
  };
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: dark ? "#709cff" : "#245bdb",
          borderRadius: 0,
          fontFamily:
            'var(--font-display), "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif',
          fontSize: 14,
          controlHeight: 40,
          colorBgBase: dark ? "#10141b" : "#fafbfd",
          colorText: dark ? "#edf2fb" : "#18202f",
          colorTextSecondary: dark ? "#a3adc0" : "#5d687b",
          colorBorder: dark ? "#303a4b" : "#d9e0ec",
          colorBgContainer: dark ? "#171e29" : "#ffffff",
          colorTextPlaceholder: dark ? "#929eb3" : "#667185",
        },
        components: {
          Button: {
            primaryShadow: "none",
            primaryColor: dark ? "#10141b" : "#ffffff",
            colorPrimary: dark ? "#709cff" : "#245bdb",
            colorPrimaryHover: dark ? "#8cafff" : "#194cc4",
            colorPrimaryActive: dark ? "#567fdf" : "#1743ac",
          },
          Card: { boxShadowTertiary: "none" },
          Table: {
            headerBg: dark ? "#1d2634" : "#f1f4f9",
            cellPaddingBlock: 16,
          },
          Tabs: { titleFontSize: 14 },
        },
      }}
    >
      <App>
        <SessionContext.Provider
          value={{ user, loading, refresh, logout, dark, toggleTheme }}
        >
          {children}
        </SessionContext.Provider>
      </App>
    </ConfigProvider>
  );
}
