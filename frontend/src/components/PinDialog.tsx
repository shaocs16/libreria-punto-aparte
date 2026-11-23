import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { ApiError } from "../api/client";
import { useToast } from "./Toast";

type Pending = { resolve: (pin: string | null) => void; error?: string };

const Ctx = createContext<{
  ask: (error?: string) => Promise<string | null>;
} | null>(null);

/**
 * Modal de PIN. El PIN solo vive en el estado del diálogo mientras está abierto y se borra al cerrarse;
 * no se guarda en contexto, storage ni caché.
 */
export function PinProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const ask = useCallback(
    (error?: string) =>
      new Promise<string | null>((resolve) => setPending({ resolve, error })),
    [],
  );
  const value = useMemo(() => ({ ask }), [ask]);
  return (
    <Ctx.Provider value={value}>
      {children}
      {pending && (
        <PinDialog
          error={pending.error}
          onSubmit={(pin) => {
            pending.resolve(pin);
            setPending(null);
          }}
          onCancel={() => {
            pending.resolve(null);
            setPending(null);
          }}
        />
      )}
    </Ctx.Provider>
  );
}

export function PinDialog({
  error,
  onSubmit,
  onCancel,
}: {
  error?: string;
  onSubmit: (pin: string) => void;
  onCancel: () => void;
}) {
  const [pin, setPin] = useState("");
  const ref = useRef<HTMLDialogElement>(null);
  const opener = useRef<Element | null>(document.activeElement);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) {
      if (typeof d.showModal === "function") d.showModal();
      else d.setAttribute("open", "");
    }
    const prev = opener.current as HTMLElement | null;
    return () => prev?.focus?.();
  }, []);

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="pin-title"
      onCancel={(e) => (e.preventDefault(), onCancel())}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (pin) onSubmit(pin);
        }}
      >
        <h2 id="pin-title">Introduce el PIN</h2>
        <p className="muted">Esta operación está protegida.</p>
        <label htmlFor="pin-input">PIN</label>
        <input
          id="pin-input"
          type="password"
          inputMode="numeric"
          autoComplete="off"
          pattern="[0-9]*"
          maxLength={8}
          autoFocus
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? "pin-error" : undefined}
        />
        {error && (
          <p id="pin-error" className="field-error" role="alert">
            {error}
          </p>
        )}
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onCancel}>
            Cancelar
          </button>
          <button
            type="submit"
            className="btn primary"
            disabled={pin.length < 4}
          >
            Confirmar
          </button>
        </div>
      </form>
    </dialog>
  );
}

/**
 * Ejecuta una operación protegida: pide el PIN, reintenta si es incorrecto y se detiene si se cancela
 * o el PIN está bloqueado/sin configurar. Devuelve undefined si no se ejecutó.
 */
export function usePinRunner() {
  const ctx = useContext(Ctx);
  const { notify } = useToast();
  if (!ctx) throw new Error("usePinRunner requiere PinProvider");
  const { ask } = ctx;

  return useCallback(
    async function run<T>(
      fn: (pin: string) => Promise<T>,
    ): Promise<T | undefined> {
      let error: string | undefined;
      for (;;) {
        const pin = await ask(error);
        if (pin === null) return undefined;
        try {
          return await fn(pin);
        } catch (e) {
          if (
            e instanceof ApiError &&
            (e.code === "PIN_INVALID" || e.code === "PIN_REQUIRED")
          ) {
            error = "PIN incorrecto. Inténtalo de nuevo.";
            continue;
          }
          if (e instanceof ApiError && e.code === "PIN_LOCKED") {
            const min = Math.ceil((e.retryAfterSeconds ?? 300) / 60);
            notify(`PIN bloqueado. Inténtalo de nuevo en ${min} min.`, "error");
            return undefined;
          }
          throw e;
        }
      }
    },
    [ask, notify],
  );
}
