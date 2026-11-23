import { useQuery } from "@tanstack/react-query";
import { Fragment, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, qs } from "../../api/client";
import type { AuditRow, Page } from "../../api/types";
import { formatDateTime } from "../../lib/format";

export function AuditPage() {
  const [sp, setSp] = useSearchParams();
  const get = (k: string) => sp.get(k) ?? "";
  const page = Number(sp.get("page") ?? 0);
  const [open, setOpen] = useState<number | null>(null);
  const params = {
    entity: get("entity") || undefined,
    id: get("id") || undefined,
    actor: get("actor") || undefined,
    from: get("from") || undefined,
    to: get("to") || undefined,
  };
  const { data } = useQuery({
    queryKey: ["audit", params, page],
    queryFn: () =>
      api<Page<AuditRow>>(`/audit${qs({ ...params, page, size: 50 })}`),
    placeholderData: (prev) => prev,
  });

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
        <h1>Auditoría</h1>
        <p>Registro inmutable de quién hizo qué y cuándo.</p>
      </section>
      <form
        className="filters"
        role="search"
        onSubmit={(e) => e.preventDefault()}
      >
        <div className="field">
          <label htmlFor="ae">Entidad</label>
          <select
            id="ae"
            value={get("entity")}
            onChange={(e) => set("entity", e.target.value)}
          >
            <option value="">Todas</option>
            {[
              "ledger_entry",
              "customer",
              "security",
              "app_setting",
              "import_job",
              "backup",
              "export",
              "system",
            ].map((x) => (
              <option key={x}>{x}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="aid">ID entidad</label>
          <input
            id="aid"
            value={get("id")}
            onChange={(e) => set("id", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="aa">Usuario</label>
          <input
            id="aa"
            value={get("actor")}
            onChange={(e) => set("actor", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="af">Desde</label>
          <input
            id="af"
            type="date"
            value={get("from")}
            onChange={(e) => set("from", e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="at">Hasta</label>
          <input
            id="at"
            type="date"
            value={get("to")}
            onChange={(e) => set("to", e.target.value)}
          />
        </div>
        <a
          className="btn secondary"
          href={`/api/v1/exports/audit.csv${exportQs}`}
          download
        >
          Exportar CSV
        </a>
        <a
          className="btn secondary"
          href={`/api/v1/exports/audit.xlsx${exportQs}`}
          download
        >
          Exportar Excel
        </a>
      </form>

      {data && data.items.length === 0 ? (
        <p className="empty-state">No hay registros con estos filtros.</p>
      ) : (
        <div
          className="table-wrap"
          tabIndex={0}
          role="region"
          aria-label="Tabla de auditoría"
        >
          <table>
            <thead>
              <tr>
                <th scope="col">Fecha</th>
                <th scope="col">Acción</th>
                <th scope="col">Entidad</th>
                <th scope="col">Usuario</th>
                <th scope="col">
                  <span className="sr-only">Detalle</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((r) => (
                <Fragment key={r.id}>
                  <tr>
                    <td>{formatDateTime(r.at)}</td>
                    <td>{r.action}</td>
                    <td>
                      {r.entity}
                      {r.entityId ? ` #${r.entityId}` : ""}
                    </td>
                    <td>{r.actorName}</td>
                    <td>
                      {(r.oldValue || r.newValue) && (
                        <button
                          type="button"
                          className="link-btn"
                          aria-expanded={open === r.id}
                          onClick={() => setOpen(open === r.id ? null : r.id)}
                        >
                          {open === r.id ? "Ocultar" : "Detalle"}
                        </button>
                      )}
                    </td>
                  </tr>
                  {open === r.id && (
                    <tr>
                      <td colSpan={5}>
                        <pre className="json">
                          {[
                            r.oldValue && `Antes: ${r.oldValue}`,
                            r.newValue && `Después: ${r.newValue}`,
                          ]
                            .filter(Boolean)
                            .join("\n")}
                        </pre>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
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
