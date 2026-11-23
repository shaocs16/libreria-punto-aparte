package com.papeleria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import com.papeleria.customer.CustomerService;
import com.papeleria.dashboard.DashboardQueries;
import com.papeleria.importer.ImportService;
import com.papeleria.security.PinService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class PerformanceAndRollbackTest {
  static final Path DB;
  static {
    try {
      DB = Files.createTempDirectory("papeleria-perf").resolve("perf.db");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.db-path", () -> DB.toString());
    r.add("app.allowed-hosts", () -> "localhost");
  }

  static final Actor A = new Actor("test", "test");

  @Autowired ImportService imports;
  @Autowired CustomerService customers;
  @Autowired DashboardQueries dashboard;
  @Autowired PinService pins;
  @Autowired JdbcTemplate jdbc;

  Long jobId(String name, String content, String kind) throws Exception {
    var job = imports.upload(kind, new MockMultipartFile("file", name, "text/csv",
        content.getBytes(StandardCharsets.UTF_8)), A);
    assertThat(job.get("errorRows")).isEqualTo(0);
    return null;
  }

  @Test
  void importExecutionIsAllOrNothing() throws Exception {
    long a = customers.create(new CustomerService.Input("RB1", "Rollback A", null, null, null), A).id();
    long b = customers.create(new CustomerService.Input("RB2", "Rollback B", null, null, null), A).id();
    var job = imports.upload("MOVEMENTS", new MockMultipartFile("file", "rb.csv", "text/csv",
        "customer_code,type,amount\nRB1,TOPUP,10\nRB2,TOPUP,10\n".getBytes(StandardCharsets.UTF_8)), A);
    // RB2 se da de baja después de validar: la ejecución falla en la segunda fila
    jdbc.update("UPDATE customer SET active = 0 WHERE id = ?", b);
    assertThatThrownBy(() -> imports.confirm((String) job.get("id"), A)).isInstanceOf(BusinessException.class);

    assertThat(jdbc.queryForObject("SELECT balance_cents FROM customer_balance WHERE customer_id = ?", Long.class, a))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE customer_id = ?", Integer.class, a))
        .isZero();
    assertThat(imports.preview((String) job.get("id")).get("status")).isEqualTo("FAILED");
  }

  @Test
  void resetPinClearsHashAndLock() {
    pins.setPin("1111", null, A);
    for (int i = 0; i < 5; i++) {
      try { pins.verifyOrThrow("0000", A); } catch (BusinessException ignored) { }
    }
    assertThatThrownBy(() -> pins.verifyOrThrow("1111", A)).extracting("code").isEqualTo("PIN_LOCKED");
    pins.reset();
    assertThat(pins.isSet()).isFalse();
    pins.setPin("2222", null, A);
    pins.verifyOrThrow("2222", A);
  }

  @Test
  void performanceTargets() throws Exception {
    int nCustomers = 10_000;
    var sb = new StringBuilder("code;name;phone\n");
    for (int i = 0; i < nCustomers; i++) {
      sb.append("P").append(i).append(";Cliente Número ").append(i).append(" Pérez;6").append(String.format("%08d", i)).append('\n');
    }
    long t0 = System.nanoTime();
    var cj = imports.upload("CUSTOMERS", new MockMultipartFile("file", "p.csv", "text/csv",
        sb.toString().getBytes(StandardCharsets.UTF_8)), A);
    imports.confirm((String) cj.get("id"), A);
    System.out.printf("PERF importación 10k clientes: %d ms%n", (System.nanoTime() - t0) / 1_000_000);

    // búsqueda p95 < 100 ms
    var times = new ArrayList<Long>();
    String[] queries = {"perez", "numero 77", "P99", "600000", "ent", "9999", "cli"};
    for (int i = 0; i < 210; i++) {
      long s = System.nanoTime();
      customers.list(queries[i % queries.length], true, false, null, 0, 50, null);
      customers.suggest(queries[i % queries.length]);
      times.add((System.nanoTime() - s) / 1_000_000);
    }
    Collections.sort(times);
    long p95 = times.get((int) (times.size() * 0.95));
    System.out.println("PERF búsqueda p95 (list+suggest): " + p95 + " ms");
    assertThat(p95).isLessThan(100);

    // 50k movimientos < 30 s
    var mv = new StringBuilder("row_id,customer_code,type,amount\n");
    for (int i = 0; i < 50_000; i++) {
      mv.append("m").append(i).append(",P").append(i % nCustomers).append(",TOPUP,1.50\n");
    }
    long t1 = System.nanoTime();
    var mj = imports.upload("MOVEMENTS", new MockMultipartFile("file", "m.csv", "text/csv",
        mv.toString().getBytes(StandardCharsets.UTF_8)), A);
    assertThat(mj.get("errorRows")).isEqualTo(0);
    System.out.println("PERF   (subida+validación 50k): " + (System.nanoTime() - t1) / 1_000_000 + " ms");
    imports.confirm((String) mj.get("id"), A);
    long ms = (System.nanoTime() - t1) / 1_000_000;
    System.out.println("PERF importación 50k movimientos: " + ms + " ms");
    assertThat(ms).isLessThan(30_000);

    long t2 = System.nanoTime();
    var d = dashboard.summary(null, null);
    long dms = (System.nanoTime() - t2) / 1_000_000;
    System.out.println("PERF dashboard: " + dms + " ms");
    assertThat(dms).isLessThan(500);
    assertThat(((Number) d.get("totalBalanceCents")).longValue()).isEqualTo(50_000L * 150);
  }
}
