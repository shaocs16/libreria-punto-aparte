import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { api, download, friendly } from "../../api/client";
import type { Health, Me, Settings } from "../../api/types";
import { usePinRunner } from "../../components/PinDialog";
import { useToast } from "../../components/Toast";
import { formatDateTime } from "../../lib/format";
import { formatCents } from "../../lib/money";

const PIN_RE = /^\d{4,8}$/;

function FirstRun({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const err =
    pin && !PIN_RE.test(pin)
      ? "El PIN debe tener entre 4 y 8 dígitos"
      : pin && pin2 && pin !== pin2
        ? "Los PIN no coinciden"
        : "";

  const m = useMutation({
    mutationFn: () => api("/security/pin", { body: { pin } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["me"] });
      notify("PIN creado.");
      onDone();
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  return (
    <section className="panel stack" aria-labelledby="fr-h">
      <h2 id="fr-h">Primer arranque: crea el PIN</h2>
      <p>
        El PIN protege las operaciones delicadas (anular, ajustar, devolver
        saldo, importar movimientos, cambiar ajustes). Elige 4 a 8 dígitos y no
        lo compartas.
      </p>
      <form
        className="stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (PIN_RE.test(pin) && pin === pin2 && !m.isPending) m.mutate();
        }}
      >
        <div className="grid-2">
          <div className="field">
            <label htmlFor="np1">PIN</label>
            <input
              id="np1"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              maxLength={8}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
            />
          </div>
          <div className="field">
            <label htmlFor="np2">Repite el PIN</label>
            <input
              id="np2"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              maxLength={8}
              value={pin2}
              onChange={(e) => setPin2(e.target.value.replace(/\D/g, ""))}
            />
          </div>
        </div>
        {err && (
          <small className="field-error" role="alert">
            {err}
          </small>
        )}
        <button
          type="submit"
          className="btn primary"
          disabled={!PIN_RE.test(pin) || pin !== pin2 || m.isPending}
        >
          Crear PIN
        </button>
      </form>
    </section>
  );
}

function ChangePin() {
  const { notify } = useToast();
  const [cur, setCur] = useState("");
  const [pin, setPin] = useState("");
  const m = useMutation({
    mutationFn: () => api("/security/pin", { body: { pin, currentPin: cur } }),
    onSuccess: () => {
      setCur("");
      setPin("");
      notify("PIN cambiado.");
    },
    onError: (e) => notify(friendly(e), "error"),
  });
  return (
    <section className="panel stack" aria-labelledby="cp-h">
      <h2 id="cp-h">Cambiar PIN</h2>
      <form
        className="grid-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (PIN_RE.test(pin) && cur && !m.isPending) m.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="cp1">PIN actual</label>
          <input
            id="cp1"
            type="password"
            inputMode="numeric"
            autoComplete="current-password"
            maxLength={8}
            value={cur}
            onChange={(e) => setCur(e.target.value.replace(/\D/g, ""))}
          />
        </div>
        <div className="field">
          <label htmlFor="cp2">PIN nuevo (4-8 dígitos)</label>
          <input
            id="cp2"
            type="password"
            inputMode="numeric"
            autoComplete="new-password"
            maxLength={8}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
          />
        </div>
        <button
          type="submit"
          className="btn secondary"
          disabled={!PIN_RE.test(pin) || !cur || m.isPending}
        >
          Cambiar PIN
        </button>
      </form>
      <p className="muted">
        ¿Olvidaste el PIN? Solo se restablece en el PC servidor con{" "}
        <code>Papeleria.exe --reset-pin</code>.
      </p>
    </section>
  );
}

export function SettingsPage() {
  const qc = useQueryClient();
  const { notify } = useToast();
  const run = usePinRunner();
  const { data: me } = useQuery({
    queryKey: ["me"],
    queryFn: () => api<Me>("/me"),
  });
  const { data: settings } = useQuery({
    queryKey: ["settings"],
    queryFn: () => api<Settings>("/settings"),
  });
  const { data: health } = useQuery({
    queryKey: ["health"],
    queryFn: () => api<Health>("/health"),
  });
  const [form, setForm] = useState<Settings>({});

  useEffect(() => {
    if (settings) setForm(settings);
  }, [settings]);

  const save = useMutation({
    mutationFn: () => {
      const changed: Settings = {};
      for (const k of Object.keys(form))
        if (k !== "last_backup_at" && form[k] !== settings?.[k])
          changed[k] = form[k];
      return run((pin) =>
        api<Settings>("/settings", { method: "PUT", body: changed, pin }),
      );
    },
    onSuccess: (r) => {
      if (!r) return;
      qc.setQueryData(["settings"], r);
      qc.invalidateQueries({ queryKey: ["health"] });
      notify("Ajustes guardados.");
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const copy = useMutation({
    mutationFn: () =>
      run((pin) =>
        download("/backup/download", "papeleria.db", { method: "POST", pin }),
      ),
    onSuccess: () => notify("Copia descargada. Guárdala fuera de este PC."),
    onError: (e) => notify(friendly(e), "error"),
  });

  const runBackup = useMutation({
    mutationFn: () =>
      run((pin) =>
        api<{ file: string }>("/backup/run", { method: "POST", pin }),
      ),
    onSuccess: (r) => {
      if (!r) return;
      qc.invalidateQueries({ queryKey: ["health"] });
      qc.invalidateQueries({ queryKey: ["settings"] });
      notify("Copia de seguridad creada.");
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  if (!settings || !me) return <p>Cargando…</p>;
  const dirty = Object.keys(form).some(
    (k) => k !== "last_backup_at" && form[k] !== settings[k],
  );
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <div className="stack-lg">
      <section className="view-heading">
        <h1>Ajustes</h1>
        <p>Los cambios están protegidos con PIN.</p>
      </section>

      {!me.pinSet ? <FirstRun onDone={() => undefined} /> : <ChangePin />}

      <form
        className="panel stack"
        aria-labelledby="neg-h"
        onSubmit={(e) => {
          e.preventDefault();
          if (dirty && !save.isPending) save.mutate();
        }}
      >
        <h2 id="neg-h">Negocio y umbrales</h2>
        <div className="grid-2">
          <div className="field">
            <label htmlFor="s1">
              Nombre del negocio (aparece en el ticket)
            </label>
            <input
              id="s1"
              value={form.business_name ?? ""}
              maxLength={100}
              onChange={(e) => set("business_name", e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="s2">Pie del ticket</label>
            <input
              id="s2"
              value={form.ticket_footer ?? ""}
              maxLength={300}
              onChange={(e) => set("ticket_footer", e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="s3">Saldo bajo (céntimos)</label>
            <input
              id="s3"
              inputMode="numeric"
              value={form.low_balance_cents ?? ""}
              onChange={(e) =>
                set("low_balance_cents", e.target.value.replace(/\D/g, ""))
              }
            />
            <small className="muted">
              {formatCents(Number(form.low_balance_cents || 0))}
            </small>
          </div>
          <div className="field">
            <label htmlFor="s4">Días para considerar inactivo</label>
            <input
              id="s4"
              inputMode="numeric"
              value={form.inactive_days ?? ""}
              onChange={(e) =>
                set("inactive_days", e.target.value.replace(/\D/g, ""))
              }
            />
          </div>
        </div>
        <h2>Copias de seguridad</h2>
        <div className="grid-2">
          <div className="field">
            <label htmlFor="s5">
              Carpeta de destino (local, USB u OneDrive)
            </label>
            <input
              id="s5"
              value={form.backup_dir ?? ""}
              onChange={(e) => set("backup_dir", e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="s6">Copia diaria automática</label>
            <select
              id="s6"
              value={form.backup_enabled ?? "false"}
              onChange={(e) => set("backup_enabled", e.target.value)}
            >
              <option value="false">Desactivada</option>
              <option value="true">Activada (22:00)</option>
            </select>
          </div>
        </div>
        <button
          type="submit"
          className="btn primary"
          disabled={!dirty || save.isPending}
        >
          Guardar ajustes 🔒
        </button>
      </form>

      <section className="panel stack" aria-labelledby="bk-h">
        <h2 id="bk-h">Estado de las copias</h2>
        <p>
          {health?.backup.configured ? (
            "Copia diaria activa."
          ) : (
            <strong>Sin copia de seguridad configurada.</strong>
          )}{" "}
          Última copia:{" "}
          {settings.last_backup_at
            ? formatDateTime(settings.last_backup_at)
            : "nunca"}
          .
        </p>
        <div className="actions left">
          <button
            type="button"
            className="btn secondary"
            disabled={copy.isPending}
            onClick={() => copy.mutate()}
          >
            Descargar copia ahora 🔒
          </button>
          <button
            type="button"
            className="btn secondary"
            disabled={!health?.backup.configured || runBackup.isPending}
            onClick={() => runBackup.mutate()}
          >
            Ejecutar copia en la carpeta de destino 🔒
          </button>
        </div>
      </section>
    </div>
  );
}
