import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { api, friendly } from "../../api/client";
import type { ImportJob, ImportRowView } from "../../api/types";
import { usePinRunner } from "../../components/PinDialog";
import { useToast } from "../../components/Toast";

const COLUMNS: Record<string, string[]> = {
  CUSTOMERS: ["code", "name", "phone", "document", "notes", "initial_balance"],
  MOVEMENTS: ["row_id", "customer_code", "type", "amount", "note"],
};

function RowsTable({
  rows,
  columns,
  title,
}: {
  rows: ImportRowView[];
  columns: string[];
  title: string;
}) {
  if (rows.length === 0) return null;
  return (
    <div className="table-wrap" tabIndex={0} role="region" aria-label={title}>
      <table>
        <thead>
          <tr>
            <th scope="col">Fila</th>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
            <th scope="col">Errores</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={r.row}
              className={r.errors.length ? "row-error" : undefined}
            >
              <td className="num">{r.row}</td>
              {columns.map((c) => (
                <td key={c}>{r.values[c] ?? ""}</td>
              ))}
              <td>
                {r.errors.map((e) => `${e.column}: ${e.message}`).join("; ")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ImportsPage() {
  const [kind, setKind] = useState<"CUSTOMERS" | "MOVEMENTS">("CUSTOMERS");
  const [jobId, setJobId] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);
  const qc = useQueryClient();
  const { notify } = useToast();
  const run = usePinRunner();

  const { data: job } = useQuery({
    queryKey: ["import", jobId],
    queryFn: () => api<ImportJob>(`/imports/${jobId}`),
    enabled: !!jobId,
  });

  const upload = useMutation({
    mutationFn: async () => {
      const f = file.current?.files?.[0];
      if (!f) throw new Error("Elige un fichero .csv o .xlsx");
      const form = new FormData();
      form.set("kind", kind);
      form.set("file", f);
      return api<ImportJob>("/imports", { form });
    },
    onSuccess: (j) => {
      setJobId(j.id);
      qc.setQueryData(["import", j.id], j);
      notify(
        j.errorRows
          ? `Fichero con ${j.errorRows} filas con errores.`
          : "Fichero válido. Revisa la vista previa y confirma.",
      );
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const confirm = useMutation({
    mutationFn: async () => {
      const exec = (pin?: string) =>
        api<ImportJob>(`/imports/${jobId}/confirm`, { method: "POST", pin });
      return job?.kind === "MOVEMENTS"
        ? await run((pin) => exec(pin))
        : await exec();
    },
    onSuccess: (j) => {
      if (!j) return;
      qc.setQueryData(["import", j.id], j);
      for (const k of [
        "customers",
        "customer",
        "ledger",
        "suggest",
        "dashboard",
      ])
        qc.invalidateQueries({ queryKey: [k] });
      notify(`Importación ejecutada: ${j.okRows} filas.`);
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const discard = useMutation({
    mutationFn: (id: string) =>
      api<void>(`/imports/${id}`, { method: "DELETE" }),
    // Solo limpia la vista si el trabajo activo sigue siendo el descartado: una subida nueva no se pisa.
    onSuccess: (_r, id) => {
      setJobId((cur) => {
        if (cur === id && file.current) file.current.value = "";
        return cur === id ? null : cur;
      });
      notify("Importación descartada.");
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const cols = COLUMNS[job?.kind ?? kind];
  return (
    <div className="stack-lg">
      <section className="view-heading">
        <h1>Importar</h1>
        <p>
          Sube un CSV o XLSX, revisa la vista previa y confirma. Todo o nada: si
          hay errores no se importa nada.
        </p>
      </section>

      <section className="panel stack">
        <form
          className="filters"
          onSubmit={(e) => {
            e.preventDefault();
            if (!upload.isPending) upload.mutate();
          }}
        >
          <div className="field">
            <label htmlFor="ik">Qué quieres importar</label>
            <select
              id="ik"
              value={kind}
              onChange={(e) => setKind(e.target.value as typeof kind)}
            >
              <option value="CUSTOMERS">
                Clientes (con saldo inicial opcional)
              </option>
              <option value="MOVEMENTS">
                Movimientos (recargas y consumos) 🔒
              </option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="if">Fichero (.csv o .xlsx, máx. 50 MB)</label>
            <input id="if" ref={file} type="file" accept=".csv,.xlsx" />
          </div>
          <button
            type="submit"
            className="btn primary"
            disabled={upload.isPending}
          >
            {upload.isPending ? "Validando…" : "Subir y validar"}
          </button>
        </form>
        <p className="muted">
          Plantillas:{" "}
          <a
            href={`/api/v1/imports/templates/${kind.toLowerCase()}.csv`}
            download
          >
            CSV
          </a>{" "}
          ·{" "}
          <a
            href={`/api/v1/imports/templates/${kind.toLowerCase()}.xlsx`}
            download
          >
            Excel
          </a>
        </p>
      </section>

      {job && (
        <section className="stack" aria-labelledby="prev-h">
          <h2 id="prev-h">Vista previa: {job.fileName}</h2>
          <p className="totals" role="status">
            {job.totalRows} filas ·{" "}
            <strong className="pos">{job.okRows} correctas</strong> ·{" "}
            <strong className={job.errorRows ? "neg" : ""}>
              {job.errorRows} con errores
            </strong>{" "}
            · Estado: {job.status}
          </p>
          {job.errorRows > 0 && (
            <>
              <h3>
                Errores{" "}
                {job.errorRows > job.errors.length
                  ? `(primeros ${job.errors.length})`
                  : ""}
              </h3>
              <RowsTable
                rows={job.errors}
                columns={cols}
                title="Filas con errores"
              />
              <p>
                <a href={`/api/v1/imports/${job.id}/errors.csv`} download>
                  Descargar errores en CSV
                </a>
              </p>
            </>
          )}
          <h3>Primeras filas</h3>
          <RowsTable rows={job.sample} columns={cols} title="Primeras filas" />
          {job.status === "VALIDATED" && (
            <div className="actions">
              <button
                type="button"
                className="btn secondary"
                disabled={discard.isPending}
                onClick={() => discard.mutate(job.id)}
              >
                Descartar
              </button>
              <button
                type="button"
                className="btn primary"
                disabled={job.errorRows > 0 || confirm.isPending}
                onClick={() => confirm.mutate()}
              >
                {confirm.isPending
                  ? "Importando… no cierres esta ventana"
                  : `Confirmar e importar ${job.kind === "MOVEMENTS" ? "🔒" : ""}`}
              </button>
            </div>
          )}
          {confirm.isPending && (
            <p className="muted">
              Mientras dura la importación, las demás operaciones esperan unos
              segundos.
            </p>
          )}
        </section>
      )}
    </div>
  );
}
