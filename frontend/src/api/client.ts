export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfterSeconds?: number;

  constructor(
    status: number,
    code: string,
    message: string,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  form?: FormData;
  idempotencyKey?: string;
  pin?: string;
  ifMatch?: number;
  signal?: AbortSignal;
}

const MESSAGES: Record<string, string> = {
  INSUFFICIENT_BALANCE: "Saldo insuficiente.",
  IDEMPOTENCY_CONFLICT:
    "Esta operación ya se envió con otros datos. Vuelve a empezar.",
  ALREADY_REVERSED: "Este movimiento ya está anulado.",
  ALREADY_A_REVERSAL:
    "Una anulación no se puede anular. Haz un movimiento nuevo.",
  STALE_VERSION: "Otra persona modificó este cliente. Recarga los datos.",
  FILE_ALREADY_IMPORTED: "Este fichero ya se importó.",
  CUSTOMER_HAS_BALANCE:
    "El cliente tiene saldo: devuélvelo o ajústalo antes de darlo de baja.",
  CUSTOMER_INACTIVE: "El cliente está de baja.",
  DUPLICATE: "Ya existe un cliente con ese código o documento.",
  PIN_NOT_SET: "Todavía no hay PIN. Créalo en Ajustes.",
  PIN_LOCKED: "PIN bloqueado temporalmente.",
  FILE_TOO_LARGE: "El fichero supera 50 MB.",
  IMPORT_HAS_ERRORS: "La importación tiene filas con errores.",
};

export function friendly(e: unknown): string {
  if (e instanceof ApiError) return MESSAGES[e.code] ?? e.message;
  if (e instanceof TypeError)
    return "No se pudo conectar con el servidor. Comprueba que está encendido.";
  return e instanceof Error ? e.message : "Error desconocido";
}

function headers(o: RequestOptions): Headers {
  const h = new Headers({
    "X-Requested-With": "papeleria-ui",
    Accept: "application/json",
  });
  if (o.body !== undefined) h.set("Content-Type", "application/json");
  if (o.idempotencyKey) h.set("Idempotency-Key", o.idempotencyKey);
  if (o.pin) h.set("X-Pin", o.pin);
  if (o.ifMatch !== undefined) h.set("If-Match", `"${o.ifMatch}"`);
  return h;
}

async function toError(res: Response): Promise<ApiError> {
  let code = "HTTP_" + res.status;
  let message = res.statusText || "Error";
  let retry: number | undefined;
  try {
    const p = await res.json();
    code = p.code ?? code;
    message = p.detail ?? p.title ?? message;
    retry = p.retryAfterSeconds;
  } catch {
    /* cuerpo no JSON */
  }
  return new ApiError(res.status, code, message, retry);
}

export async function apiRaw(
  path: string,
  o: RequestOptions = {},
): Promise<Response> {
  const res = await fetch(`/api/v1${path}`, {
    method: o.method ?? (o.body !== undefined || o.form ? "POST" : "GET"),
    headers: headers(o),
    body: o.form ?? (o.body !== undefined ? JSON.stringify(o.body) : undefined),
    signal: o.signal,
  });
  if (!res.ok) throw await toError(res);
  return res;
}

export async function api<T>(path: string, o: RequestOptions = {}): Promise<T> {
  const res = await apiRaw(path, o);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export function qs(
  params: Record<string, string | number | boolean | undefined | null>,
): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") u.set(k, String(v));
  }
  const s = u.toString();
  return s ? `?${s}` : "";
}

/** Descarga un fichero (con las cabeceras de la app) y lo entrega al navegador. */
export async function download(
  path: string,
  fallbackName: string,
  o: RequestOptions = {},
): Promise<void> {
  const res = await apiRaw(path, o);
  const blob = await res.blob();
  const cd = res.headers.get("Content-Disposition") ?? "";
  const name = /filename="?([^";]+)"?/.exec(cd)?.[1] ?? fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
