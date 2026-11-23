package com.papeleria.health;

import com.papeleria.backup.BackupService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
  private final ReconciliationService reconciliation;
  private final BackupService backups;

  public HealthController(ReconciliationService reconciliation, BackupService backups) {
    this.reconciliation = reconciliation;
    this.backups = backups;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    var r = reconciliation.last();
    return Map.of(
        "status", r == null || r.ok() ? "UP" : "DEGRADED",
        "reconciliation", r == null ? "pending" : r,
        "backup", backups.status());
  }
}
