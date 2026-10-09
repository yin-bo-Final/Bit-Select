"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { loginHref } from "./login-redirect";

export function useLoginHref() {
  const pathname = usePathname() || "/";
  const [location, setLocation] = useState<{
    pathname: string;
    href: string;
  }>();
  useEffect(() => {
    const update = () =>
      setLocation({
        pathname,
        href: loginHref(
          window.location.pathname +
            window.location.search +
            window.location.hash,
        ),
      });
    update();
    window.addEventListener("popstate", update);
    window.addEventListener("hashchange", update);
    return () => {
      window.removeEventListener("popstate", update);
      window.removeEventListener("hashchange", update);
    };
  }, [pathname]);
  return location?.pathname === pathname ? location.href : loginHref(pathname);
}
