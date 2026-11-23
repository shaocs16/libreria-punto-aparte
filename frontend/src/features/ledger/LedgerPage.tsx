import { useSearchParams } from "react-router-dom";
import { qs } from "../../api/client";
import { useLedger } from "../../api/hooks";
import { LedgerTable } from "../../components/LedgerTable";
import { TYPE_LABELS } from "../../lib/format";
import { formatCents, parseEuros } from "../../lib/money";

export function LedgerPage() {
  const [sp, setSp] = useSearchParams();
  const get = (k: string) => sp.get(k) ?? "";
  const page = Number(sp.get("page") ?? 0);

  const minC = get("min") ? parseEuros(get("min")) : null;
  const maxC = get("max") ? parseEuros(get("max")) : null;
  const params = {
    customerId: get("customerId") || undefined,
    from: get("from") || undefined,
    to: get("to") || undefined,
    type: get("type") || undefined,
    min: minC ?? undefined,
    max: maxC ?? undefined,
    actor: get("actor") || undefined,
  };
  const { data, isFetching } = useLedger({ ...params, page, size: 50 });

  function set(k: string, v: string) {
    const n = new URLSearchParams(sp);
    if (v) n.set(k, v);
    else n.delete(k);
    if (k !== "page") n.delete("page");
    setSp(n, { replace: true });
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  const exportQs = qs(params);
  return (
    <div className="stack-lg">
      <section className="view-heading">
        <h1>Movimientos</h1>
        <p>Historial completo con filtros y totales del filtro.</p>
      </section>

      <form
        className="filters"
        role="search"
        onSubmit={(e) => e.preventDefault()}
      >
        <div className="field">
          <label htmlFor="lf">Desde</label>
          <input
            id="lf"
            type="date"
            value={get("from")}
            onChange={(e) => set("from", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="lt">Hasta</label>
          <input
            id="lt"
            type="date"
            value={get("to")}
            onChange={(e) => set("to", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="ltype">Tipo</label>
          <select
            id="ltype"
            value={get("type")}
            onChange={(e) => set("type", e.target.value)}
          >
            <option value="">Todos</option>
            {Object.entries(TYPE_LABELS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="lmin">Importe mínimo (€)</label>
          <input
            id="lmin"
            inputMode="decimal"
            value={get("min")}
            onChange={(e) => set("min", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="lmax">Importe máximo (€)</label>
          <input
            id="lmax"
            inputMode="decimal"
            value={get("max")}
            onChange={(e) => set("max", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="lactor">Usuario</label>
          <input
            id="lactor"
            value={get("actor")}
            onChange={(e) => set("actor", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="lcust">ID de cliente</label>
          <input
            id="lcust"
            inputMode="numeric"
            value={get("customerId")}
            onChange={(e) =>
              set("customerId", e.target.value.replace(/\D/g, ""))
            }
          />
        </div>
        <a
          className="btn secondary"
          href={`/api/v1/exports/ledger.csv${exportQs}`}
          download
        >
          Exportar CSV
        </a>
        <a
          className="btn secondary"
          href={`/api/v1/exports/ledger.xlsx${exportQs}`}
          download
        >
          Exportar Excel
        </a>
      </form>

      {data && (
        <p className="totals" aria-live="polite">
          {data.total} movimientos · Entradas{" "}
          <strong className="num pos">
            {formatCents(data.totals.inCents)}
          </strong>{" "}
          · Salidas{" "}
          <strong className="num neg">
            {formatCents(data.totals.outCents)}
          </strong>
        </p>
      )}
      <div className={isFetching ? "busy" : ""}>
        <LedgerTable entries={data?.items ?? []} showCustomer />
      </div>
      <nav className="pager" aria-label="Paginación">
        <button
          type="button"
          className="btn secondary"
          disabled={page <= 0}
          onClick={() => set("page", String(page - 1))}
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
          onClick={() => set("page", String(page + 1))}
        >
          Siguiente
        </button>
      </nav>
    </div>
  );
}
