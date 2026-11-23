export type EntryType = "TOPUP" | "SPEND" | "REFUND" | "ADJUST" | "REVERSAL";

export interface Customer {
  id: number;
  code: string;
  name: string;
  phone?: string;
  document?: string;
  notes?: string;
  active: boolean;
  version: number;
  balanceCents: number;
  lastEntryAt?: string;
}

export interface LedgerEntry {
  id: number;
  customerId: number;
  type: EntryType;
  amountCents: number;
  balanceAfterCents: number;
  reversesEntryId?: number;
  note?: string;
  source: "UI" | "IMPORT" | "SYSTEM";
  actor: string;
  actorLogin: string;
  createdAt: string;
  idempotencyKey: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface LedgerPage extends Page<LedgerEntry> {
  totals: { inCents: number; outCents: number };
}

export interface Me {
  login: string;
  name: string;
  pinSet: boolean;
}

export interface Reconciliation {
  ok: boolean;
  checkedAt: string;
  mismatchedCustomers: number[];
  integrity: string;
}

export interface Health {
  status: "UP" | "DEGRADED";
  reconciliation: Reconciliation | "pending";
  backup: {
    configured: boolean;
    lastBackupAt?: string;
    lastError?: string;
    warning?: string;
  };
}

export interface Suggestion {
  id: number;
  code: string;
  name: string;
  phone?: string;
  balanceCents: number;
}

export interface DashboardSummary {
  totalBalanceCents: number;
  series: { day: string; topupsCents: number; spendsCents: number }[];
  totals: {
    topupsCents: number;
    spendsCents: number;
    refundsCents: number;
    adjustmentsCents: number;
  };
  topCustomersBySpend: {
    id: number;
    code: string;
    name: string;
    spendCents: number;
  }[];
  topCustomersByBalance: {
    id: number;
    code: string;
    name: string;
    balanceCents: number;
  }[];
  inactiveCustomers: number;
  lowBalanceCustomers: number;
  averageTicketCents: number;
  reconciliation: Reconciliation | "pending";
}

export interface AuditRow {
  id: number;
  at: string;
  action: string;
  entity: string;
  entityId?: string;
  oldValue?: string;
  newValue?: string;
  actorLogin: string;
  actorName: string;
}

export interface Receipt {
  businessName: string;
  footer: string;
  entry: LedgerEntry;
  customer: { id: number; code: string; name: string };
}

export interface ImportRowView {
  row: number;
  values: Record<string, string>;
  errors: { column: string; message: string }[];
}

export interface ImportJob {
  id: string;
  kind: "CUSTOMERS" | "MOVEMENTS";
  fileName: string;
  status: "VALIDATED" | "EXECUTED" | "FAILED" | "DISCARDED";
  totalRows: number;
  okRows: number;
  errorRows: number;
  createdAt: string;
  executedAt?: string;
  errors: ImportRowView[];
  sample: ImportRowView[];
}

export type Settings = Record<string, string>;
