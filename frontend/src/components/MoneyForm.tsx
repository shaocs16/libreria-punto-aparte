import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, friendly } from "../api/client";
import type { LedgerEntry } from "../api/types";
import { useIdempotencyKey } from "../hooks/useIdempotencyKey";
import { formatCents } from "../lib/money";
import { MoneyInput } from "./MoneyInput";
import { usePinRunner } from "./PinDialog";
import { useToast } from "./Toast";

export type Op = "topups" | "spends" | "refunds" | "adjustments";

export const OP_INFO: Record<
  Op,
  {
    title: string;
    verb: string;
    pin: boolean;
    noteRequired: boolean;
    signed?: boolean;
  }
> = {
  topups: {
    title: "Recarga",
    verb: "Recargar",
    pin: false,
    noteRequired: false,
  },
  spends: { title: "Consumo", verb: "Cobrar", pin: false, noteRequired: false },
  refunds: {
    title: "Devolución de saldo",
    verb: "Devolver",
    pin: true,
    noteRequired: true,
  },
  adjustments: {
    title: "Ajuste manual",
    verb: "Aplicar ajuste",
    pin: true,
    noteRequired: true,
    signed: true,
  },
};

const QUICK = [100, 200, 500, 1000, 2000];

interface Props {
  customerId: number;
  op: Op;
  quick?: boolean;
  onDone?: (entry: LedgerEntry) => void;
}

/**
 * Formulario de una operación de dinero. Protecciones de mostrador:
 *  - botón deshabilitado mientras se envía (doble clic)
 *  - Idempotency-Key estable entre reintentos y renovada tras éxito
 *  - PIN pedido en modal solo para operaciones protegidas
 */
export function MoneyForm({ customerId, op, quick, onDone }: Props) {
  const info = OP_INFO[op];
  const [cents, setCents] = useState<number | null>(null);
  const [note, setNote] = useState("");
  const key = useIdempotencyKey();
  const qc = useQueryClient();
  const { notify } = useToast();
  const runWithPin = usePinRunner();

  const send = (pin?: string) =>
    api<LedgerEntry>(`/customers/${customerId}/${op}`, {
      body: { amountCents: cents, note: note.trim() || undefined },
      idempotencyKey: key.get(),
      pin,
    });

  const m = useMutation({
    mutationFn: async () =>
      info.pin ? await runWithPin((pin) => send(pin)) : await send(),
    onSuccess: (entry) => {
      if (!entry) return; // PIN cancelado
      key.rotate();
      setCents(null);
      setNote("");
      for (const k of [
        "customer",
        "customers",
        "ledger",
        "suggest",
        "dashboard",
      ])
        qc.invalidateQueries({ queryKey: [k] });
      notify(
        `${info.title} registrada. Saldo: ${formatCents(entry.balanceAfterCents)}`,
      );
      onDone?.(entry);
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const valid =
    cents !== null &&
    (info.signed ? cents !== 0 : cents > 0) &&
    (!info.noteRequired || note.trim().length > 0);

  return (
    <form
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid && !m.isPending) m.mutate();
      }}
    >
      <MoneyInput
        label={info.signed ? "Importe (negativo resta)" : "Importe"}
        valueCents={cents}
        onChangeCents={setCents}
        allowNegative={!!info.signed}
        autoFocus={op === "spends" || op === "topups"}
      />
      {quick && (
        <div className="chips" role="group" aria-label="Importes rápidos">
          {QUICK.map((q) => (
            <button
              key={q}
              type="button"
              className="chip"
              onClick={() => setCents((cents ?? 0) + q)}
            >
              +{formatCents(q)}
            </button>
          ))}
          <button type="button" className="chip" onClick={() => setCents(null)}>
            Borrar
          </button>
        </div>
      )}
      <div className="field">
        <label htmlFor={`note-${op}`}>
          {info.noteRequired ? "Motivo (obligatorio)" : "Nota (opcional)"}
        </label>
        <input
          id={`note-${op}`}
          value={note}
          maxLength={500}
          onChange={(e) => setNote(e.target.value)}
        />
      </div>
      <button
        className={`btn ${op === "spends" ? "primary" : "secondary-strong"}`}
        type="submit"
        disabled={!valid || m.isPending}
      >
        {m.isPending
          ? "Enviando…"
          : `${info.verb}${cents ? " " + formatCents(Math.abs(cents)) : ""}`}
        {info.pin ? " 🔒" : ""}
      </button>
    </form>
  );
}
