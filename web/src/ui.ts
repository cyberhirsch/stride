export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, any> = {},
  ...children: (Node | string | null | undefined | false)[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "class") el.className = v;
    else if (k in el && typeof v !== "string") (el as any)[k] = v;
    else el.setAttribute(k, v === true ? "" : String(v));
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) el.append(c);
  return el;
}

/** Opens a modal using the shared .modal skeleton; returns a close function. */
export function openModal(title: string, body: Node, footer?: Node, onClose?: () => void) {
  const root = document.getElementById("modal-root")!;
  const close = () => {
    modal.remove();
    document.removeEventListener("keydown", onKey);
    onClose?.();
  };
  const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
  const modal = h(
    "div",
    { class: "modal", onclick: (e: MouseEvent) => e.target === modal && close() },
    h(
      "div",
      { class: "modal-content", role: "dialog", "aria-label": title },
      h("div", { class: "modal-header" }, h("h2", {}, title), h("button", { class: "icon", onclick: close, "aria-label": "Close" }, "×")),
      h("div", { class: "modal-body" }, body),
      footer ? h("div", { class: "modal-footer" }, footer) : null,
    ),
  );
  document.addEventListener("keydown", onKey);
  root.append(modal);
  return close;
}

let toastTimer: number | undefined;
export function toast(msg: string) {
  const el = document.getElementById("toast")!;
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (el.hidden = true), 3500);
}

export function errorMessage(err: unknown): string {
  const e = err as any;
  const fields = e?.response?.data;
  if (fields && typeof fields === "object") {
    const first = Object.entries(fields)[0] as [string, any] | undefined;
    if (first) return `${first[0]}: ${first[1]?.message ?? "invalid"}`;
  }
  return e?.response?.message || e?.message || String(err);
}

export const fmt = {
  deg: (v: number, digits = 0) => `${v.toFixed(digits)}°`,
  date: (iso: string) =>
    new Date(iso.replace(" ", "T")).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }),
  compass: (deg: number) => ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Math.round(deg / 45) % 8],
};
