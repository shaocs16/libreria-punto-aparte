import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Route, HashRouter, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { PinProvider } from "./components/PinDialog";
import { ToastProvider } from "./components/Toast";
import { AuditPage } from "./features/audit/AuditPage";
import { CashierPage } from "./features/cashier/CashierPage";
import { CustomerDetailPage } from "./features/customers/CustomerDetailPage";
import { CustomersPage } from "./features/customers/CustomersPage";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { ImportsPage } from "./features/imports/ImportsPage";
import { LedgerPage } from "./features/ledger/LedgerPage";
import { ReceiptPage } from "./features/ledger/ReceiptPage";
import { SettingsPage } from "./features/settings/SettingsPage";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: true, staleTime: 5_000 },
  },
});

export function NotFound() {
  return (
    <section className="view-heading">
      <h1>Esta página no existe.</h1>
      <p>
        <a href="#/">Volver a la caja</a>
      </p>
    </section>
  );
}

// HashRouter: los enlaces profundos funcionan sin configurar rutas de fallback en Spring.
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <PinProvider>
          <HashRouter>
            <Routes>
              <Route element={<Layout />}>
                <Route index element={<CashierPage />} />
                <Route path="clientes" element={<CustomersPage />} />
                <Route path="clientes/:id" element={<CustomerDetailPage />} />
                <Route path="movimientos" element={<LedgerPage />} />
                <Route path="panel" element={<DashboardPage />} />
                <Route path="importar" element={<ImportsPage />} />
                <Route path="auditoria" element={<AuditPage />} />
                <Route path="ajustes" element={<SettingsPage />} />
                <Route path="ticket/:id" element={<ReceiptPage />} />
                <Route path="*" element={<NotFound />} />
              </Route>
            </Routes>
          </HashRouter>
        </PinProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
