import { useState } from "react";
import { Link } from "react-router-dom";
import { useCustomer, useLedger } from "../../api/hooks";
import type { LedgerEntry, Suggestion } from "../../api/types";
import { CustomerPicker } from "../../components/CustomerPicker";
import { LedgerTable } from "../../components/LedgerTable";
import { MoneyForm } from "../../components/MoneyForm";
import { formatCents } from "../../lib/money";

export function CashierPage() {
  const [picked, setPicked] = useState<Suggestion | null>(null);
  const [mode, setMode] = useState<"spends" | "topups">("spends");
  const [last, setLast] = useState<LedgerEntry | null>(null);
  const { data: customer } = useCustomer(picked?.id);
  const { data: recent } = useLedger(
    { customerId: picked?.id, size: 5 },
    !!picked,
  );

  return (
    <div className="stack-lg">
      <section className="view-heading">
        <h1>Caja</h1>
        <p>Busca al cliente, elige recarga o consumo e introduce el importe.</p>
      </section>

      <section className="panel" aria-label="Cliente">
        <CustomerPicker
          autoFocus
          onPick={(s) => {
            setPicked(s);
            setLast(null);
          }}
        />
      </section>

      {customer && (
        <>
          <section className="panel balance-panel" aria-live="polite">
            <div>
              <p className="muted">
                {customer.code} ·{" "}
                <Link to={`/clientes/${customer.id}`}>{customer.name}</Link>
              </p>
              <p className="balance" data-testid="balance">
                {formatCents(customer.balanceCents)}
              </p>
              <p className="muted">Saldo disponible</p>
            </div>
            <button
              type="button"
              className="btn secondary"
              onClick={() => setPicked(null)}
            >
              Cambiar cliente
            </button>
          </section>

          {!customer.active ? (
            <div className="banner warn" role="note">
              Este cliente está de baja: no se puede operar con él.
            </div>
          ) : (
            <section className="panel">
              <div
                className="segmented"
                role="radiogroup"
                aria-label="Tipo de operación"
              >
                {(["spends", "topups"] as const).map((m) => (
                  <button
                    key={m}
                    type="button"
                    role="radio"
                    aria-checked={mode === m}
                    className={mode === m ? "on" : ""}
                    onClick={() => setMode(m)}
                  >
                    {m === "spends" ? "Consumo" : "Recarga"}
                  </button>
                ))}
              </div>
              <MoneyForm
                key={`${customer.id}-${mode}`}
                customerId={customer.id}
                op={mode}
                quick
                onDone={setLast}
              />
              {last && (
                <p className="success-line" role="status">
                  Operación registrada.{" "}
                  <Link to={`/ticket/${last.id}`}>Ver / imprimir ticket</Link>
                </p>
              )}
            </section>
          )}

          <section aria-labelledby="recent-h">
            <h2 id="recent-h">Últimos movimientos</h2>
            <LedgerTable entries={recent?.items ?? []} />
          </section>
        </>
      )}
    </div>
  );
}
