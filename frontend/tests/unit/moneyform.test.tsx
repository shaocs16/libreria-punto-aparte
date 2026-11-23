import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MoneyForm } from "../../src/components/MoneyForm";
import { PinProvider } from "../../src/components/PinDialog";
import { ToastProvider } from "../../src/components/Toast";

function wrap(ui: ReactNode) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <PinProvider>{ui}</PinProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

const entry = {
  id: 1,
  customerId: 7,
  type: "SPEND",
  amountCents: -250,
  balanceAfterCents: 750,
  source: "UI",
  actor: "t",
  actorLogin: "t",
  createdAt: "2026-11-18T10:00:00Z",
  idempotencyKey: "k",
};
const problem = (status: number, code: string) =>
  new Response(JSON.stringify({ status, code, detail: code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
const ok = (body: unknown, status = 201) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

afterEach(() => vi.restoreAllMocks());

describe("MoneyForm: protecciones de mostrador", () => {
  it("el doble clic envía una sola petición, con Idempotency-Key y cabecera de la app", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(
        () => new Promise((r) => setTimeout(() => r(ok(entry)), 50)),
      );
    wrap(<MoneyForm customerId={7} op="spends" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Importe"), "2,50");
    const btn = screen.getByRole("button", { name: /Cobrar/ });
    await user.dblClick(btn);

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "Consumo registrada",
      ),
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/customers/7/spends");
    const h = new Headers((init as RequestInit).headers);
    expect(h.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    expect(h.get("X-Requested-With")).toBe("papeleria-ui");
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      amountCents: 250,
    });
  });

  it("reintenta con la MISMA clave tras un fallo de red y usa otra tras el éxito", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValueOnce(new TypeError("network"))
      .mockResolvedValueOnce(ok(entry))
      .mockResolvedValueOnce(ok({ ...entry, id: 2 }));
    wrap(<MoneyForm customerId={7} op="spends" />);
    const user = userEvent.setup();
    const send = async () => {
      await user.clear(screen.getByLabelText("Importe"));
      await user.type(screen.getByLabelText("Importe"), "2,50");
      await user.click(screen.getByRole("button", { name: /Cobrar/ }));
    };
    await send();
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "No se pudo conectar",
      ),
    );
    await send();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("registrada"),
    );
    await send();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));

    const key = (i: number) =>
      new Headers((fetchMock.mock.calls[i][1] as RequestInit).headers).get(
        "Idempotency-Key",
      );
    expect(key(1)).toBe(key(0));
    expect(key(2)).not.toBe(key(0));
  });

  it("no permite enviar importes vacíos o con más de 2 decimales", async () => {
    wrap(<MoneyForm customerId={7} op="topups" />);
    const user = userEvent.setup();
    const btn = screen.getByRole("button", { name: /Recargar/ });
    expect(btn).toBeDisabled();
    await user.type(screen.getByLabelText("Importe"), "1,234");
    expect(btn).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("máximo 2 decimales");
  });
});

describe("PIN en operaciones protegidas", () => {
  it("pide el PIN, repite si es incorrecto y lo envía en X-Pin", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(problem(403, "PIN_INVALID"))
      .mockResolvedValueOnce(
        ok({ ...entry, type: "REFUND", amountCents: -500 }),
      );
    wrap(<MoneyForm customerId={7} op="refunds" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Importe"), "5");
    await user.type(
      screen.getByLabelText("Motivo (obligatorio)"),
      "cliente se va",
    );
    await user.click(screen.getByRole("button", { name: /Devolver/ }));

    await user.type(await screen.findByLabelText("PIN"), "0000");
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "PIN incorrecto",
    );

    await user.type(screen.getByLabelText("PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    const pins = fetchMock.mock.calls.map((c) =>
      new Headers((c[1] as RequestInit).headers).get("X-Pin"),
    );
    expect(pins[0]).toBe("0000");
    expect(pins[1]).toBe("1234");
    await waitFor(() =>
      expect(screen.queryByLabelText("PIN")).not.toBeInTheDocument(),
    );
  });

  it("cancelar el modal no envía nada", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    wrap(<MoneyForm customerId={7} op="adjustments" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Importe/), "-2");
    await user.type(
      screen.getByLabelText("Motivo (obligatorio)"),
      "error de caja",
    );
    await user.click(screen.getByRole("button", { name: /Aplicar ajuste/ }));
    await user.click(await screen.findByRole("button", { name: "Cancelar" }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("PIN bloqueado: avisa con los minutos y no vuelve a pedir", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "PIN_LOCKED",
          detail: "x",
          retryAfterSeconds: 240,
        }),
        { status: 423 },
      ),
    );
    wrap(<MoneyForm customerId={7} op="refunds" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Importe"), "5");
    await user.type(screen.getByLabelText("Motivo (obligatorio)"), "x");
    await user.click(screen.getByRole("button", { name: /Devolver/ }));
    await user.type(await screen.findByLabelText("PIN"), "1234");
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("4 min"),
    );
    expect(screen.queryByLabelText("PIN")).not.toBeInTheDocument();
  });
});
