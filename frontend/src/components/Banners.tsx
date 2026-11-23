import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Health } from "../api/types";
import { formatDateTime } from "../lib/format";

/** Avisos permanentes: sin backup, reconciliación fallida o servidor no disponible. */
export function Banners() {
  const { data, isError } = useQuery({
    queryKey: ["health"],
    queryFn: () => api<Health>("/health"),
    refetchInterval: 60_000,
  });

  return (
    <div className="banners">
      {isError && (
        <div className="banner danger" role="alert">
          No hay conexión con el servidor de la papelería. No se puede operar
          hasta que vuelva.
        </div>
      )}
      {data && data.reconciliation !== "pending" && !data.reconciliation.ok && (
        <div className="banner danger" role="alert">
          <strong>Los saldos no cuadran con los movimientos</strong> (
          {data.reconciliation.mismatchedCustomers.length} clientes afectados,
          revisado {formatDateTime(data.reconciliation.checkedAt)}). Avisa a
          quien administra el sistema.
        </div>
      )}
      {data && !data.backup.configured && (
        <div className="banner warn" role="note">
          <strong>Sin copia de seguridad configurada.</strong> Si el disco falla
          se perderían todos los saldos.
        </div>
      )}
      {data?.backup.lastError && (
        <div className="banner danger" role="alert">
          La última copia de seguridad falló: {data.backup.lastError}
        </div>
      )}
    </div>
  );
}
