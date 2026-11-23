package com.papeleria.health;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final Actor SYSTEM = new Actor("system", "system");

  public record Status(boolean ok, String checkedAt, List<Long> mismatchedCustomers, String integrity) {}

  private final JdbcTemplate read;
  private final AuditService audit;
  private final AtomicReference<Status> last = new AtomicReference<>();

  public ReconciliationService(@Qualifier("readJdbc") JdbcTemplate read, AuditService audit) {
    this.read = read;
    this.audit = audit;
  }

  public Status last() { return last.get(); }

  @EventListener(ApplicationReadyEvent.class)
  public void onStart() { run(false); }

  @Scheduled(cron = "0 0 3 * * *")
  public void nightly() { run(false); }

  @Scheduled(cron = "0 30 3 * * SUN")
  public void weekly() { run(true); }

  public synchronized Status run(boolean integrity) {
    var bad = read.queryForList("""
        SELECT b.customer_id FROM customer_balance b
         WHERE b.balance_cents <> COALESCE((SELECT SUM(amount_cents) FROM ledger_entry e
                                             WHERE e.customer_id = b.customer_id), 0)
        """, Long.class);
    String integ = integrity ? read.queryForObject("PRAGMA integrity_check", String.class)
        : (last.get() == null ? "not-run" : last.get().integrity());
    var s = new Status(bad.isEmpty() && (!integrity || "ok".equals(integ)),
        java.time.Instant.now().toString(), bad, integ);
    last.set(s);
    if (!s.ok()) {
      log.error("Reconciliación fallida: clientes {} integridad {}", bad, integ);
      audit.record("RECONCILIATION_FAILED", "system", null, null,
          Map.of("customers", bad, "integrity", integ), SYSTEM);
    }
    return s;
  }
}
