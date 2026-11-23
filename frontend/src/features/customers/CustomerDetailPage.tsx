import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router-dom";
import { api, friendly } from "../../api/client";
import type { Customer } from "../../api/types";
import { useCustomer, useLedger } from "../../api/hooks";
import { LedgerTable } from "../../components/LedgerTable";
import { MoneyForm, OP_INFO, type Op } from "../../components/MoneyForm";
import { usePinRunner } from "../../components/PinDialog";
import { useToast } from "../../components/Toast";
import { formatCents } from "../../lib/money";
import { CustomerForm } from "./CustomerForm";

const OPS: Op[] = ["topups", "spends", "refunds", "adjustments"];

export function CustomerDetailPage() {
  const id = Number(useParams().id);
  const { data: c, isLoading, error } = useCustomer(id);
  const [op, setOp] = useState<Op>("topups");
  const [page, setPage] = useState(0);
  const { data: ledger } = useLedger({ customerId: id, page, size: 20 });
  const [editing, setEditing] = useState(false);
  const qc = useQueryClient();
  const { notify } = useToast();
  const run = usePinRunner();

  const toggle = useMutation({
    mutationFn: (active: boolean) =>
      run((pin) =>
        api<Customer>(
          `/customers/${id}/${active ? "reactivate" : "deactivate"}`,
          { method: "POST", pin },
        ),
      ),
    onSuccess: (r) => {
      if (!r) return;
      for (const k of ["customer", "customers", "suggest"])
        qc.invalidateQueries({ queryKey: [k] });
      notify(r.active ? "Cliente reactivado." : "Cliente dado de baja.");
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  if (isLoading) return <p>Cargando…</p>;
  if (error || !c)
    return (
      <section className="view-heading">
        <h1>Cliente no encontrado</h1>
        <p>
          <a href="#/clientes">Volver a clientes</a>
        </p>
      </section>
    );

  const pages = ledger ? Math.max(1, Math.ceil(ledger.total / ledger.size)) : 1;
  return (
    <div className="stack-lg">
      <section className="view-heading row-between">
        <div>
          <p className="muted">{c.code}</p>
          <h1>
            {c.name} {!c.active && <span className="badge">De baja</span>}
          </h1>
          <p className="muted">
            {[c.phone, c.document].filter(Boolean).join(" · ") ||
              "Sin teléfono ni documento"}
          </p>
        </div>
        <div className="balance-box">
          <p className="muted">Saldo</p>
          <p className="balance" data-testid="balance">
            {formatCents(c.balanceCents)}
          </p>
        </div>
      </section>

      {c.active ? (
        <section className="panel" aria-labelledby="op-h">
          <h2 id="op-h">Operar</h2>
          <div
            className="segmented"
            role="radiogroup"
            aria-label="Tipo de operación"
          >
            {OPS.map((o) => (
              <button
                key={o}
                type="button"
                role="radio"
                aria-checked={op === o}
                className={op === o ? "on" : ""}
                onClick={() => setOp(o)}
              >
                {OP_INFO[o].title}
                {OP_INFO[o].pin ? " 🔒" : ""}
              </button>
            ))}
          </div>
          <MoneyForm key={op} customerId={c.id} op={op} />
        </section>
      ) : (
        <div className="banner warn" role="note">
          Cliente de baja: solo consulta. Reactívalo para operar.
        </div>
      )}

      <section className="panel" aria-labelledby="data-h">
        <div className="row-between">
          <h2 id="data-h">Datos del cliente</h2>
          <div className="actions">
            <button
              type="button"
              className="btn secondary"
              onClick={() => setEditing((v) => !v)}
            >
              {editing ? "Cerrar edición" : "Editar"}
            </button>
            <button
              type="button"
              className="btn secondary"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate(!c.active)}
            >
              {c.active ? "Dar de baja 🔒" : "Reactivar 🔒"}
            </button>
          </div>
        </div>
        {editing ? (
          <CustomerForm
            key={c.version}
            customer={c}
            onSaved={() => setEditing(false)}
            onCancel={() => setEditing(false)}
          />
        ) : (
          <dl className="details">
            <dt>Notas</dt>
            <dd>{c.notes || "—"}</dd>
          </dl>
        )}
      </section>

      <section aria-labelledby="hist-h">
        <h2 id="hist-h">Historial</h2>
        <LedgerTable entries={ledger?.items ?? []} />
        <nav className="pager" aria-label="Paginación del historial">
          <button
            type="button"
            className="btn secondary"
            disabled={page <= 0}
            onClick={() => setPage(page - 1)}
          >
            Anterior
          </button>
          <span>
            Página {page + 1} de {pages}
          </span>
          <button
            type="button"
            className="btn secondary"
            disabled={page + 1 >= pages}
            onClick={() => setPage(page + 1)}
          >
            Siguiente
          </button>
        </nav>
      </section>
    </div>
  );
}
