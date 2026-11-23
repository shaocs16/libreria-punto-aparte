import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import type { Receipt } from "../../api/types";
import { formatDateTime, TYPE_LABELS } from "../../lib/format";
import { formatCents } from "../../lib/money";

/** Ticket imprimible (80 mm o A4): el CSS @media print oculta el resto de la interfaz. */
export function ReceiptPage() {
  const id = Number(useParams().id);
  const { data, isLoading, error } = useQuery({
    queryKey: ["receipt", id],
    queryFn: () => api<Receipt>(`/ledger/${id}/receipt`),
  });
  if (isLoading) return <p>Cargando…</p>;
  if (error || !data)
    return (
      <section className="view-heading">
        <h1>Movimiento no encontrado</h1>
      </section>
    );
  const e = data.entry;
  return (
    <div className="stack-lg">
      <div className="no-print row-between">
        <h1>Ticket #{e.id}</h1>
        <button
          type="button"
          className="btn primary"
          onClick={() => window.print()}
        >
          Imprimir
        </button>
      </div>
      <article className="receipt" aria-label="Ticket">
        <h2>{data.businessName}</h2>
        <p>{formatDateTime(e.createdAt)}</p>
        <hr />
        <p>
          Cliente: <strong>{data.customer.name}</strong> ({data.customer.code})
        </p>
        <p>
          {TYPE_LABELS[e.type]}:{" "}
          <strong className="num">
            {formatCents(Math.abs(e.amountCents))}
          </strong>
        </p>
        {e.note && <p>Nota: {e.note}</p>}
        <p>
          Saldo tras la operación:{" "}
          <strong className="num">{formatCents(e.balanceAfterCents)}</strong>
        </p>
        <p className="muted">Atendido por {e.actor}</p>
        <hr />
        <p>{data.footer}</p>
      </article>
    </div>
  );
}
