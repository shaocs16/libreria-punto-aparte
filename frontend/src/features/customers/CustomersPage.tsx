import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, qs } from "../../api/client";
import type { Customer, Page } from "../../api/types";
import { formatDate } from "../../lib/format";
import { formatCents } from "../../lib/money";
import { CustomerForm } from "./CustomerForm";

export function CustomersPage() {
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const [creating, setCreating] = useState(false);
  const dlg = useRef<HTMLDialogElement>(null);

  const q = sp.get("q") ?? "";
  const filter = sp.get("filter") ?? "active";
  const sort = sp.get("sort") ?? "name";
  const page = Number(sp.get("page") ?? 0);

  const params = {
    q,
    active: filter === "inactive" ? false : filter === "all" ? undefined : true,
    lowBalance: filter === "low" ? true : undefined,
    inactiveDays: filter === "dormant" ? 90 : undefined,
    sort,
    page,
    size: 25,
  };
  const { data, isFetching } = useQuery({
    queryKey: ["customers", params],
    queryFn: () => api<Page<Customer>>(`/customers${qs(params)}`),
    placeholderData: (prev) => prev,
  });

  useEffect(() => {
    if (creating) dlg.current?.showModal?.();
  }, [creating]);

  function set(k: string, v: string) {
    const n = new URLSearchParams(sp);
    if (v) n.set(k, v);
    else n.delete(k);
    if (k !== "page") n.delete("page");
    setSp(n, { replace: true });
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  return (
    <div className="stack-lg">
      <section className="view-heading row-between">
        <div>
          <h1>Clientes</h1>
          <p>{data ? `${data.total} clientes` : "Cargando…"}</p>
        </div>
        <button
          type="button"
          className="btn primary"
          onClick={() => setCreating(true)}
        >
          Nuevo cliente
        </button>
      </section>

      <form
        className="filters"
        role="search"
        onSubmit={(e) => e.preventDefault()}
      >
        <div className="field">
          <label htmlFor="cq">Buscar</label>
          <input
            id="cq"
            value={q}
            onChange={(e) => set("q", e.target.value)}
            placeholder="Nombre, código, teléfono…"
          />
        </div>
        <div className="field">
          <label htmlFor="cf">Mostrar</label>
          <select
            id="cf"
            value={filter}
            onChange={(e) => set("filter", e.target.value)}
          >
            <option value="active">Activos</option>
            <option value="low">Saldo bajo</option>
            <option value="dormant">Inactivos (+90 días con saldo)</option>
            <option value="inactive">De baja</option>
            <option value="all">Todos</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="cs">Ordenar por</label>
          <select
            id="cs"
            value={sort}
            onChange={(e) => set("sort", e.target.value)}
          >
            <option value="name">Nombre</option>
            <option value="code">Código</option>
            <option value="balance">Saldo (mayor primero)</option>
            <option value="balanceAsc">Saldo (menor primero)</option>
            <option value="recent">Último movimiento</option>
          </select>
        </div>
        <a
          className="btn secondary"
          href="/api/v1/exports/customers.csv"
          download
        >
          Exportar CSV
        </a>
        <a
          className="btn secondary"
          href="/api/v1/exports/customers.xlsx"
          download
        >
          Exportar Excel
        </a>
      </form>

      {data && data.items.length === 0 ? (
        <p className="empty-state">No hay clientes que coincidan.</p>
      ) : (
        <div
          className={`table-wrap ${isFetching ? "busy" : ""}`}
          tabIndex={0}
          role="region"
          aria-label="Tabla de clientes"
        >
          <table>
            <thead>
              <tr>
                <th scope="col">Código</th>
                <th scope="col">Nombre</th>
                <th scope="col">Teléfono</th>
                <th scope="col" className="r">
                  Saldo
                </th>
                <th scope="col">Último movimiento</th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td>
                    <Link to={`/clientes/${c.id}`}>{c.name}</Link>
                    {!c.active && <span className="badge">De baja</span>}
                  </td>
                  <td>{c.phone ?? ""}</td>
                  <td className="r num">{formatCents(c.balanceCents)}</td>
                  <td>{formatDate(c.lastEntryAt)}</td>
                </tr>
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

      {creating && (
        <dialog
          ref={dlg}
          className="dialog wide"
          aria-labelledby="new-title"
          onClose={() => setCreating(false)}
        >
          <h2 id="new-title">Nuevo cliente</h2>
          <CustomerForm
            onCancel={() => dlg.current?.close()}
            onSaved={(c) => {
              dlg.current?.close();
              nav(`/clientes/${c.id}`);
            }}
          />
        </dialog>
      )}
    </div>
  );
}
