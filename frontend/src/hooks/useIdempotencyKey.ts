import { useCallback, useRef } from "react";

export function newKey(): string {
  const c: Crypto | undefined =
    typeof crypto !== "undefined" ? crypto : undefined;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const b = new Uint8Array(16);
  if (c) c.getRandomValues(b);
  else for (let i = 0; i < 16; i++) b[i] = Math.floor(Math.random() * 256);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * Una clave por intención de operación. Si el envío falla (red, timeout) se reutiliza la misma clave
 * en el reintento, así el servidor no duplica el movimiento. Tras un éxito hay que llamar a rotate().
 */
export function useIdempotencyKey() {
  const ref = useRef<string>(newKey());
  const get = useCallback(() => ref.current, []);
  const rotate = useCallback(() => {
    ref.current = newKey();
  }, []);
  return { get, rotate };
}
