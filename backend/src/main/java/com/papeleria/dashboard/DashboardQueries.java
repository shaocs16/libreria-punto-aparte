package com.papeleria.dashboard;

import com.papeleria.common.TimeRange;
import com.papeleria.health.ReconciliationService;
import com.papeleria.settings.SettingsService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardQueries {
  private final JdbcTemplate read;
  private final SettingsService settings;
  private final ReconciliationService reconciliation;

  public DashboardQueries(@Qualifier("readJdbc") JdbcTemplate read, SettingsService settings,
                          ReconciliationService reconciliation) {
    this.read = read;
    this.settings = settings;
    this.reconciliation = reconciliation;
  }

  /** from/to en formato "yyyy-MM-dd" (to inclusivo); por defecto, los últimos 30 días. */
  public Map<String, Object> summary(String fromDate, String toDate) {
    String today = LocalDate.now(TimeRange.ZONE).toString();
    String from = TimeRange.from(fromDate != null ? fromDate : LocalDate.parse(today).minusDays(29).toString());
    String to = TimeRange.to(toDate != null ? toDate : today);
    int low = settings.getInt("low_balance_cents");
    int inactiveDays = settings.getInt("inactive_days");
    String inactiveLimit = TimeRange.format(Instant.now().minus(inactiveDays, ChronoUnit.DAYS));

    var out = new LinkedHashMap<String, Object>();
    out.put("totalBalanceCents", read.queryForObject(
        "SELECT COALESCE(SUM(balance_cents),0) FROM customer_balance", Long.class));

    List<Map<String, Object>> series = read.queryForList("""
        SELECT date(created_at,'localtime') AS day,
               COALESCE(SUM(CASE WHEN effective_type='TOPUP' THEN amount_cents END),0)  AS topupsCents,
              -COALESCE(SUM(CASE WHEN effective_type='SPEND' THEN amount_cents END),0)  AS spendsCents
          FROM v_ledger_effective
         WHERE created_at >= ? AND created_at < ?
         GROUP BY day ORDER BY day
        """, from, to);
    out.put("series", series);
    out.put("totals", read.queryForMap("""
        SELECT COALESCE(SUM(CASE WHEN effective_type='TOPUP'  THEN amount_cents END),0)  AS topupsCents,
              -COALESCE(SUM(CASE WHEN effective_type='SPEND'  THEN amount_cents END),0)  AS spendsCents,
              -COALESCE(SUM(CASE WHEN effective_type='REFUND' THEN amount_cents END),0)  AS refundsCents,
               COALESCE(SUM(CASE WHEN effective_type='ADJUST' THEN amount_cents END),0)  AS adjustmentsCents
          FROM v_ledger_effective WHERE created_at >= ? AND created_at < ?
        """, from, to));
    out.put("topCustomersBySpend", read.queryForList("""
        SELECT c.id, c.code, c.name, -SUM(e.amount_cents) AS spendCents
          FROM v_ledger_effective e JOIN customer c ON c.id = e.customer_id
         WHERE e.effective_type = 'SPEND' AND e.created_at >= ? AND e.created_at < ?
         GROUP BY c.id HAVING -SUM(e.amount_cents) > 0
         ORDER BY spendCents DESC LIMIT 10
        """, from, to));
    out.put("topCustomersByBalance", read.queryForList("""
        SELECT c.id, c.code, c.name, b.balance_cents AS balanceCents
          FROM customer_balance b JOIN customer c ON c.id = b.customer_id
         WHERE b.balance_cents > 0 ORDER BY b.balance_cents DESC LIMIT 10
        """));
    out.put("inactiveCustomers", read.queryForObject("""
        SELECT COUNT(*) FROM customer_balance b JOIN customer c ON c.id = b.customer_id
         WHERE c.active = 1 AND b.balance_cents > 0 AND (b.last_entry_at IS NULL OR b.last_entry_at < ?)
        """, Long.class, inactiveLimit));
    out.put("lowBalanceCustomers", read.queryForObject("""
        SELECT COUNT(*) FROM customer_balance b JOIN customer c ON c.id = b.customer_id
         WHERE c.active = 1 AND b.balance_cents > 0 AND b.balance_cents <= ?
        """, Long.class, low));
    out.put("averageTicketCents", read.queryForObject("""
        SELECT COALESCE(CAST(ROUND(AVG(-e.amount_cents)) AS INTEGER),0) FROM ledger_entry e
         WHERE e.type = 'SPEND' AND e.created_at >= ? AND e.created_at < ?
           AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = e.id)
        """, Long.class, from, to));
    out.put("reconciliation", reconciliation.last() == null ? "pending" : reconciliation.last());
    return out;
  }
}
