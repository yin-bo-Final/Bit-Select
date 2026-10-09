const localOrigin = "https://bit-select.invalid";

export function resolveLoginReturnPath(
  value: string | null | undefined,
): string {
  if (
    !value?.startsWith("/") ||
    value.startsWith("//") ||
    /[\\\u0000-\u001f\u007f]/.test(value)
  )
    return "/";
  try {
    const target = new URL(value, localOrigin);
    const pathname = decodeURIComponent(target.pathname);
    if (
      target.origin !== localOrigin ||
      pathname.startsWith("//") ||
      /[\\\u0000-\u001f\u007f]/.test(pathname) ||
      pathname === "/login" ||
      pathname.startsWith("/login/")
    )
      return "/";
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return "/";
  }
}

export function loginHref(returnPath: string): string {
  return `/login?next=${encodeURIComponent(resolveLoginReturnPath(returnPath))}`;
}
