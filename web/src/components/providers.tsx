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
  effectsEnabled: boolean;
  toggleEffects: () => void;
};
const SessionContext = createContext<Session>({
  user: null,
  loading: true,
  refresh: async () => {},
  logout: async () => {},
  dark: false,
  toggleTheme: () => {},
  effectsEnabled: true,
  toggleEffects: () => {},
});
export const useSession = () => useContext(SessionContext);
export function Providers({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [dark, setDark] = useState(false);
  const [themeReady, setThemeReady] = useState(false);
  const [effectsEnabled, setEffectsEnabled] = useState(true);
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
    try {
      setEffectsEnabled(localStorage.getItem("bit-select-effects") !== "off");
    } catch {}
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
  useEffect(() => {
    if (themeReady)
      document.documentElement.dataset.effects = effectsEnabled ? "on" : "off";
  }, [effectsEnabled, themeReady]);
  const toggleEffects = () =>
    setEffectsEnabled((value) => {
      try {
        localStorage.setItem("bit-select-effects", value ? "off" : "on");
      } catch {}
      return !value;
    });
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
          colorPrimary: dark ? "#91b5ff" : "#225cff",
          borderRadius: 20,
          borderRadiusLG: 32,
          borderRadiusSM: 12,
          fontFamily:
            'var(--font-display), "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif',
          fontSize: 14,
          controlHeight: 44,
          colorBgBase: dark ? "#080e1c" : "#f3f6fc",
          colorText: dark ? "#f0f5ff" : "#101d36",
          colorTextSecondary: dark ? "#b8c9e3" : "#475872",
          colorBorder: dark ? "#2a3d5b" : "#d6e1f2",
          colorBgContainer: dark ? "#111d32" : "#ffffff",
          colorTextPlaceholder: dark ? "#a0b3d0" : "#5c6d87",
        },
        components: {
          Button: {
            borderRadius: 999,
            primaryShadow: "0 5px 18px rgb(34 92 255 / 15%)",
            primaryColor: dark ? "#080e1c" : "#ffffff",
            colorPrimary: dark ? "#91b5ff" : "#225cff",
            colorPrimaryHover: dark ? "#b3cdff" : "#1747d3",
            colorPrimaryActive: dark ? "#739aec" : "#123bb7",
          },
          Input: { borderRadius: 999, paddingInline: 16 },
          InputNumber: { borderRadius: 999, controlWidth: 100 },
          Select: {
            borderRadius: 999,
            optionSelectedBg: dark ? "#233a60" : "#e4edff",
          },
          Tag: { borderRadiusSM: 999 },
          Modal: { borderRadiusLG: 32 },
          Dropdown: { borderRadiusLG: 22 },
          Collapse: { borderRadiusLG: 24 },
          Card: { boxShadowTertiary: "none" },
          Table: {
            headerBg: dark ? "#182842" : "#eaf0fa",
            cellPaddingBlock: 16,
          },
          Tabs: { titleFontSize: 14 },
        },
      }}
    >
      <App>
        <SessionContext.Provider
          value={{
            user,
            loading,
            refresh,
            logout,
            dark,
            toggleTheme,
            effectsEnabled,
            toggleEffects,
          }}
        >
          {children}
        </SessionContext.Provider>
      </App>
    </ConfigProvider>
  );
}
