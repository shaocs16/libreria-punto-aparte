import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, friendly } from "../api/client";
import type { LedgerEntry } from "../api/types";
import { useIdempotencyKey } from "../hooks/useIdempotencyKey";
import { formatDateTime, TYPE_LABELS } from "../lib/format";
import { formatCents } from "../lib/money";
import { usePinRunner } from "./PinDialog";
import { useToast } from "./Toast";

function ReverseDialog({
  entry,
  onClose,
}: {
  entry: LedgerEntry;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [reason, setReason] = useState("");
  const key = useIdempotencyKey();
  const run = usePinRunner();
  const qc = useQueryClient();
  const { notify } = useToast();

  useEffect(() => {
    ref.current?.showModal?.();
  }, []);

  const m = useMutation({
    mutationFn: () =>
      run((pin) =>
        api<LedgerEntry>(`/ledger/${entry.id}/reversal`, {
          body: { reason: reason.trim() },
          pin,
          idempotencyKey: key.get(),
        }),
      ),
    onSuccess: (r) => {
      if (!r) return;
      for (const k of [
        "customer",
        "customers",
        "ledger",
        "suggest",
        "dashboard",
      ])
        qc.invalidateQueries({ queryKey: [k] });
      notify(`Movimiento anulado. Saldo: ${formatCents(r.balanceAfterCents)}`);
      onClose();
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="rev-title"
      onCancel={(e) => (e.preventDefault(), onClose())}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (reason.trim() && !m.isPending) m.mutate();
        }}
      >
        <h2 id="rev-title">Anular movimiento #{entry.id}</h2>
        <p>
          {TYPE_LABELS[entry.type]} de{" "}
          <strong className="num">
            {formatCents(Math.abs(entry.amountCents))}
          </strong>{" "}
          del {formatDateTime(entry.createdAt)}. Se creará un contramovimiento;
          el original no se borra.
        </p>
        <div className="field">
          <label htmlFor="rev-reason">Motivo (obligatorio)</label>
          <input
            id="rev-reason"
            autoFocus
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onClose}>
            Cancelar
          </button>
          <button
            type="submit"
            className="btn danger"
            disabled={!reason.trim() || m.isPending}
          >
            {m.isPending ? "Anulando…" : "Anular 🔒"}
          </button>
        </div>
      </form>
    </dialog>
  );
}

export function LedgerTable({
  entries,
  showCustomer,
  canReverse = true,
}: {
  entries: LedgerEntry[];
  showCustomer?: boolean;
  canReverse?: boolean;
}) {
  const [target, setTarget] = useState<LedgerEntry | null>(null);
  const reversed = new Set(
    entries.filter((e) => e.reversesEntryId).map((e) => e.reversesEntryId),
  );

  if (entries.length === 0)
    return <p className="empty-state">No hay movimientos con estos filtros.</p>;
  return (
    <>
      <div
        className="table-wrap"
        tabIndex={0}
        role="region"
        aria-label="Tabla de movimientos"
      >
        <table>
          <thead>
            <tr>
              <th scope="col">Fecha</th>
              {showCustomer && <th scope="col">Cliente</th>}
              <th scope="col">Tipo</th>
              <th scope="col" className="r">
                Importe
              </th>
              <th scope="col" className="r">
                Saldo
              </th>
              <th scope="col">Nota</th>
              <th scope="col">Usuario</th>
              <th scope="col">
                <span className="sr-only">Acciones</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => {
              const isReversed = reversed.has(e.id);
              return (
                <tr key={e.id} className={isReversed ? "struck" : undefined}>
                  <td>{formatDateTime(e.createdAt)}</td>
                  {showCustomer && (
                    <td>
                      <Link to={`/clientes/${e.customerId}`}>
                        #{e.customerId}
                      </Link>
                    </td>
                  )}
                  <td>
                    <span className={`badge ${e.type.toLowerCase()}`}>
                      {TYPE_LABELS[e.type]}
                    </span>
                    {e.source === "IMPORT" && (
                      <span className="badge">Importado</span>
                    )}
                    {isReversed && <span className="badge">Anulado</span>}
                  </td>
                  <td className={`r num ${e.amountCents < 0 ? "neg" : "pos"}`}>
                    {e.amountCents > 0 ? "+" : ""}
                    {formatCents(e.amountCents)}
                  </td>
                  <td className="r num">{formatCents(e.balanceAfterCents)}</td>
                  <td>{e.note ?? ""}</td>
                  <td>{e.actor}</td>
                  <td className="row-actions">
                    <Link to={`/ticket/${e.id}`} className="link-btn">
                      Ticket
                    </Link>
                    {canReverse && e.type !== "REVERSAL" && !isReversed && (
                      <button
                        type="button"
                        className="link-btn danger-text"
                        onClick={() => setTarget(e)}
                      >
                        Anular
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {target && (
        <ReverseDialog entry={target} onClose={() => setTarget(null)} />
      )}
    </>
  );
}
