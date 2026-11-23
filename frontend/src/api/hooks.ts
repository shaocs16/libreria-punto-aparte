import { useQuery } from "@tanstack/react-query";
import { api, qs } from "../api/client";
import type { Customer, LedgerPage } from "../api/types";

export function useCustomer(id: number | undefined) {
  return useQuery({
    queryKey: ["customer", id],
    queryFn: () => api<Customer>(`/customers/${id}`),
    enabled: id !== undefined && Number.isFinite(id),
  });
}

export function useLedger(
  params: Record<string, string | number | boolean | undefined>,
  enabled = true,
) {
  return useQuery({
    queryKey: ["ledger", params],
    queryFn: () => api<LedgerPage>(`/ledger${qs(params)}`),
    enabled,
    placeholderData: (prev) => prev,
  });
}
