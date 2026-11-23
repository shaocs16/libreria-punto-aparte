import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Link } from "react-router-dom";
import { api, qs } from "../../api/client";
import type { DashboardSummary } from "../../api/types";
import { formatDateTime, todayIso } from "../../lib/format";
import { formatCents } from "../../lib/money";

export function DashboardPage() {
  const [from, setFrom] = useState(todayIso(-29));
  const [to, setTo] = useState(todayIso());
  const { data, isLoading } = useQuery({
    queryKey: ["dashboard", from, to],
    queryFn: () =>
      api<DashboardSummary>(`/dashboard/summary${qs({ from, to })}`),
    placeholderData: (prev) => prev,
  });

  if (isLoading || !data) return <p>Cargando…</p>;
  const rec = data.reconciliation;
  const chart = data.series.map((s) => ({
    day: s.day.slice(5),
    Recargas: s.topupsCents / 100,
    Consumos: s.spendsCents / 100,
  }));

  return (
    <div className="stack-lg">
      <section className="view-heading row-between">
        <div>
          <h1>Panel</h1>
          <p>Todas las cifras salen de los movimientos reales.</p>
        </div>
        <p
          className={`status-pill ${rec === "pending" ? "" : rec.ok ? "ok" : "bad"}`}
          role="status"
        >
          {rec === "pending"
            ? "Cuadre pendiente"
            : rec.ok
              ? `Saldos cuadrados (${formatDateTime(rec.checkedAt)})`
              : "Los saldos NO cuadran"}
        </p>
      </section>

      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="df">Desde</label>
          <input
            id="df"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="dt">Hasta</label>
          <input
            id="dt"
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
      </form>

      <dl className="stats">
        <div>
          <dt>Saldo total en circulación</dt>
          <dd className="num">{formatCents(data.totalBalanceCents)}</dd>
        </div>
        <div>
          <dt>Recargas del periodo</dt>
          <dd className="num">{formatCents(data.totals.topupsCents)}</dd>
        </div>
        <div>
          <dt>Consumos del periodo</dt>
          <dd className="num">{formatCents(data.totals.spendsCents)}</dd>
        </div>
        <div>
          <dt>Ticket medio</dt>
          <dd className="num">{formatCents(data.averageTicketCents)}</dd>
        </div>
        <div>
          <dt>Devoluciones</dt>
          <dd className="num">{formatCents(data.totals.refundsCents)}</dd>
        </div>
        <div>
          <dt>Con saldo bajo</dt>
          <dd>
            <Link to="/clientes?filter=low">{data.lowBalanceCustomers}</Link>
          </dd>
        </div>
        <div>
          <dt>Inactivos con saldo</dt>
          <dd>
            <Link to="/clientes?filter=dormant">{data.inactiveCustomers}</Link>
          </dd>
        </div>
      </dl>

      <section className="panel" aria-labelledby="ch-h">
        <h2 id="ch-h">Recargas y consumos por día</h2>
        {chart.length === 0 ? (
          <p className="empty-state">No hay movimientos en este periodo.</p>
        ) : (
          <div
            className="chart"
            role="img"
            aria-label="Gráfico de barras de recargas y consumos por día"
          >
            <ResponsiveContainer width="100%" height={280}>
              <BarChart data={chart}>
                <CartesianGrid strokeDasharray="3 3" stroke="#eaecea" />
                <XAxis dataKey="day" />
                <YAxis tickFormatter={(v) => `${v} €`} width={60} />
                <Tooltip
                  formatter={(v: number) => formatCents(Math.round(v * 100))}
                />
                <Legend />
                <Bar
                  dataKey="Recargas"
                  fill="#1b6e66"
                  isAnimationActive={false}
                />
                <Bar
                  dataKey="Consumos"
                  fill="#a15c1a"
                  isAnimationActive={false}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </section>

      <div className="grid-2">
        <section aria-labelledby="t1">
          <h2 id="t1">Top por consumo</h2>
          <div
            className="table-wrap"
            tabIndex={0}
            role="region"
            aria-label="Top por consumo"
          >
            <table>
              <thead>
                <tr>
                  <th scope="col">Cliente</th>
                  <th scope="col" className="r">
                    Consumo
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.topCustomersBySpend.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <Link to={`/clientes/${c.id}`}>{c.name}</Link>
                    </td>
                    <td className="r num">{formatCents(c.spendCents)}</td>
                  </tr>
                ))}
                {data.topCustomersBySpend.length === 0 && (
                  <tr>
                    <td colSpan={2} className="muted">
                      Sin datos
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
        <section aria-labelledby="t2">
          <h2 id="t2">Top por saldo</h2>
          <div
            className="table-wrap"
            tabIndex={0}
            role="region"
            aria-label="Top por saldo"
          >
            <table>
              <thead>
                <tr>
                  <th scope="col">Cliente</th>
                  <th scope="col" className="r">
                    Saldo
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.topCustomersByBalance.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <Link to={`/clientes/${c.id}`}>{c.name}</Link>
                    </td>
                    <td className="r num">{formatCents(c.balanceCents)}</td>
                  </tr>
                ))}
                {data.topCustomersByBalance.length === 0 && (
                  <tr>
                    <td colSpan={2} className="muted">
                      Sin datos
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  );
}
