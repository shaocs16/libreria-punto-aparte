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

interface ToastApi {
  notify: (message: string, kind?: "info" | "error") => void;
}

const Ctx = createContext<ToastApi>({ notify: () => {} });
export const useToast = () => useContext(Ctx);

/** Región role=status: los lectores de pantalla anuncian el resultado de cada operación. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState<{
    text: string;
    kind: "info" | "error";
  } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>();

  const notify = useCallback(
    (text: string, kind: "info" | "error" = "info") => {
      setMsg({ text, kind });
      clearTimeout(timer.current);
      timer.current = setTimeout(
        () => setMsg(null),
        kind === "error" ? 6000 : 3500,
      );
    },
    [],
  );
  useEffect(() => () => clearTimeout(timer.current), []);
  const value = useMemo(() => ({ notify }), [notify]);

  return (
    <Ctx.Provider value={value}>
      {children}
      <div
        className={`toast ${msg ? "show" : ""} ${msg?.kind === "error" ? "error" : ""}`}
        role="status"
        aria-live="polite"
      >
        {msg?.text}
      </div>
    </Ctx.Provider>
  );
}
