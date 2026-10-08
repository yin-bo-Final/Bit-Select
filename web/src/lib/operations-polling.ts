"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText } from "@/lib/api";

export function useOperationsPolling<T>(
  path: string | null,
  intervalMs = 15000,
) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(!!path);
  const [refreshing, setRefreshing] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [visible, setVisible] = useState(true);
  const [resourcePath, setResourcePath] = useState(path);
  const autoRefreshRef = useRef(autoRefresh);
  const run = useRef<(() => void) | null>(null);
  const preferenceChanged = useRef<(() => void) | null>(null);
  autoRefreshRef.current = autoRefresh;

  useEffect(() => {
    let disposed = false;
    let controller: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let hasLoaded = false;
    setResourcePath(path);
    setData(null);
    setError("");
    setUpdatedAt(null);
    setLoading(!!path);
    setRefreshing(false);
    setVisible(!document.hidden);

    const clearTimer = () => {
      if (timer) clearTimeout(timer);
      timer = null;
    };
    const schedule = () => {
      clearTimer();
      if (!disposed && path && !document.hidden && autoRefreshRef.current)
        timer = setTimeout(() => void load(), intervalMs);
    };
    const load = async () => {
      if (disposed || !path || document.hidden || controller) return;
      clearTimer();
      const request = new AbortController();
      controller = request;
      setRefreshing(true);
      try {
        const result = await api<T>(path, { signal: request.signal });
        if (disposed || request.signal.aborted || controller !== request)
          return;
        setData(result);
        setError("");
        setUpdatedAt(new Date());
      } catch (failure) {
        if (!disposed && !request.signal.aborted && controller === request)
          setError(errorText(failure));
      } finally {
        if (controller === request) {
          controller = null;
          if (!disposed) {
            hasLoaded = true;
            setRefreshing(false);
            setLoading(false);
            schedule();
          }
        }
      }
    };
    const visibility = () => {
      setVisible(!document.hidden);
      if (document.hidden) {
        clearTimer();
        controller?.abort();
        controller = null;
        setRefreshing(false);
      } else if (autoRefreshRef.current || !hasLoaded) void load();
    };
    const control = () => {
      if (autoRefreshRef.current) void load();
      else clearTimer();
    };
    run.current = () => void load();
    preferenceChanged.current = control;
    document.addEventListener("visibilitychange", visibility);
    void load();
    return () => {
      disposed = true;
      clearTimer();
      controller?.abort();
      document.removeEventListener("visibilitychange", visibility);
      run.current = null;
      preferenceChanged.current = null;
    };
  }, [path, intervalMs]);

  const refresh = useCallback(() => run.current?.(), []);
  const changeAutoRefresh = useCallback((enabled: boolean) => {
    autoRefreshRef.current = enabled;
    setAutoRefresh(enabled);
    preferenceChanged.current?.();
  }, []);
  const current = resourcePath === path;
  return {
    data: current ? data : null,
    error: current ? error : "",
    loading: current ? loading : !!path,
    refreshing: current ? refreshing : !!path,
    updatedAt: current ? updatedAt : null,
    autoRefresh,
    visible,
    setAutoRefresh: changeAutoRefresh,
    refresh,
  };
}
