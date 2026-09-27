"use client";
import { App, ConfigProvider, theme } from "antd";
import zhCN from "antd/locale/zh_CN";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
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
  const refresh = useCallback(async () => {
    try {
      setUser(await api<User>("/auth/me"));
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    const preference = localStorage.getItem("bit-select-theme");
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    setDark(preference ? preference === "dark" : media.matches);
    const listener = (e: MediaQueryListEvent) => {
      if (!localStorage.getItem("bit-select-theme")) setDark(e.matches);
    };
    media.addEventListener("change", listener);
    return () => media.removeEventListener("change", listener);
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [dark]);
  const toggleTheme = () =>
    setDark((value) => {
      localStorage.setItem("bit-select-theme", value ? "light" : "dark");
      return !value;
    });
  const logout = async () => {
    await post("/auth/logout");
    setUser(null);
  };
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: "#315bce",
          borderRadius: 8,
          fontFamily:
            '"Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif',
          fontSize: 14,
          controlHeight: 40,
          colorBgBase: dark ? "#17191e" : "#fcfcfd",
        },
        components: {
          Button: { primaryShadow: "none" },
          Card: { boxShadowTertiary: "none" },
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
