/* This document stays readable without JavaScript. No account or tracking data is read. */
(() => {
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const syncTheme = () => {
    let preference = null;
    try {
      preference = localStorage.getItem("bit-select-theme");
    } catch {}
    document.documentElement.dataset.theme =
      preference === "dark" || (preference !== "light" && media.matches)
        ? "dark"
        : "light";
  };
  syncTheme();
  media.addEventListener("change", syncTheme);
  window.addEventListener("storage", (event) => {
    if (event.key === "bit-select-theme" || event.key === null) syncTheme();
  });
  const enhance = () => {
    const documentBody = document.querySelector("pre");
    if (!documentBody) return;
    const original = documentBody.textContent;
    const fragment = document.createDocumentFragment();
    for (const line of original.split(/(?<=\n)/)) {
      const heading = line.match(/^(#{1,3}) /);
      const element = document.createElement("span");
      if (heading) {
        element.className = `manual-heading manual-heading-${heading[1].length}`;
        element.setAttribute("role", "heading");
        element.setAttribute("aria-level", String(heading[1].length));
        const marker = document.createElement("span");
        marker.className = "manual-marker";
        marker.textContent = heading[0];
        element.append(
          marker,
          document.createTextNode(line.slice(heading[0].length)),
        );
      } else {
        element.textContent = line;
        if (/^(文档编号：|来源：)/.test(line))
          element.className = "manual-meta";
        else if (line.startsWith("问：")) element.className = "manual-question";
        else if (line.startsWith("- ")) element.className = "manual-spec-label";
      }
      fragment.append(element);
    }
    // Enhancement must preserve every character, including Markdown markers and line breaks.
    if (fragment.textContent === original)
      documentBody.replaceChildren(fragment);
  };
  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", enhance, { once: true });
  else enhance();
})();
