package com.papeleria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import com.papeleria.customer.CustomerService;
import com.papeleria.ledger.LedgerService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

@SpringBootTest
class LedgerIntegrationTest {
  static final Path DB;
  static {
    try {
      DB = Files.createTempDirectory("papeleria-test").resolve("test.db");
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
  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired CustomerService customers;
  @Autowired LedgerService ledger;
  @Autowired JdbcTemplate jdbc;

  long newCustomer(long topUpCents) {
    int n = SEQ.incrementAndGet();
    var c = customers.create(new CustomerService.Input("C" + n, "Cliente " + n, null, null, null), A);
    if (topUpCents > 0) ledger.topUp(UUID.randomUUID(), c.id(), topUpCents, null, A);
    return c.id();
  }

  long balance(long id) {
    return jdbc.queryForObject("SELECT balance_cents FROM customer_balance WHERE customer_id=?", Long.class, id);
  }

  @Test
  void autoCodesAreSequentialAndNeverCollide() throws Exception {
    // un cliente con código manual alto: el siguiente automático continúa por encima
    customers.create(new CustomerService.Input("C500", "Manual", null, null, null), A);
    var a = customers.create(new CustomerService.Input(null, "Auto A", null, null, null), A);
    var b = customers.create(new CustomerService.Input("  ", "Auto B", null, null, null), A);
    assertThat(a.code()).isEqualTo("C501");
    assertThat(b.code()).isEqualTo("C502");

    // 20 altas simultáneas: todos los códigos distintos y con el formato esperado
    var pool = Executors.newFixedThreadPool(10);
    var futures = new ArrayList<java.util.concurrent.Future<String>>();
    for (int i = 0; i < 20; i++) {
      int n = i;
      futures.add(pool.submit(() -> customers.create(
          new CustomerService.Input(null, "Paralelo " + n, null, null, null), A).code()));
    }
    var codes = new java.util.HashSet<String>();
    for (var f : futures) codes.add(f.get());
    pool.shutdown();
    assertThat(codes).hasSize(20).allMatch(c -> c.matches("C\\d{3,}"));
  }

  @Test
  void codeCannotBeChangedOnUpdate() {
    long id = newCustomer(0);
    var c = customers.get(id);
    // cambiar el código se rechaza
    assertThatThrownBy(() -> customers.update(id, c.version(),
        new CustomerService.Input("OTRO-99", "Nombre nuevo", null, null, null), A))
        .extracting("code").isEqualTo("VALIDATION_ERROR");
    // reenviar el mismo código, o dejarlo vacío, permite editar el resto y conserva el código
    var same = customers.update(id, c.version(), new CustomerService.Input(c.code(), "Nombre nuevo", null, null, null), A);
    assertThat(same.code()).isEqualTo(c.code());
    assertThat(same.name()).isEqualTo("Nombre nuevo");
    var blank = customers.update(id, same.version(), new CustomerService.Input(" ", "Otro nombre", null, null, null), A);
    assertThat(blank.code()).isEqualTo(c.code());
    assertThat(blank.name()).isEqualTo("Otro nombre");
  }

  @Test
  void fiftyParallelSpendsOnlyTenSucceed() throws Exception {
    long id = newCustomer(10_000);
    var pool = Executors.newFixedThreadPool(16);
    var latch = new CountDownLatch(1);
    var ok = new AtomicInteger();
    var insufficient = new AtomicInteger();
    var futures = new ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < 50; i++) {
      futures.add(pool.submit(() -> {
        latch.await();
        try {
          ledger.spend(UUID.randomUUID(), id, 1_000, null, A);
          ok.incrementAndGet();
        } catch (BusinessException e) {
          if (e.code().equals("INSUFFICIENT_BALANCE")) insufficient.incrementAndGet();
        }
        return null;
      }));
    }
    latch.countDown();
    for (var f : futures) f.get();
    pool.shutdown();
    assertThat(ok.get()).isEqualTo(10);
    assertThat(insufficient.get()).isEqualTo(40);
    assertThat(balance(id)).isZero();
  }

  @Test
  void sameKeyTwentyTimesCreatesOneEntry() throws Exception {
    long id = newCustomer(5_000);
    var key = UUID.randomUUID();
    var pool = Executors.newFixedThreadPool(10);
    var futures = new ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> ledger.spend(key, id, 500, null, A)));
    for (var f : futures) f.get();
    pool.shutdown();
    assertThat(balance(id)).isEqualTo(4_500);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE idempotency_key=?",
        Integer.class, key.toString())).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentBodyConflicts() {
    long id = newCustomer(5_000);
    var key = UUID.randomUUID();
    ledger.spend(key, id, 500, null, A);
    assertThatThrownBy(() -> ledger.spend(key, id, 700, null, A))
        .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void reversalRules() {
    long id = newCustomer(0);
    var topup = ledger.topUp(UUID.randomUUID(), id, 3_000, null, A).entry();
    var spend = ledger.spend(UUID.randomUUID(), id, 1_000, null, A).entry();

    var rev = ledger.reverse(UUID.randomUUID(), spend.id(), "error", A).entry();
    assertThat(rev.amountCents()).isEqualTo(1_000);
    assertThat(balance(id)).isEqualTo(3_000);

    assertThatThrownBy(() -> ledger.reverse(UUID.randomUUID(), spend.id(), "otra", A))
        .extracting("code").isEqualTo("ALREADY_REVERSED");
    assertThatThrownBy(() -> ledger.reverse(UUID.randomUUID(), rev.id(), "x", A))
        .extracting("code").isEqualTo("ALREADY_A_REVERSAL");

    ledger.spend(UUID.randomUUID(), id, 3_000, null, A);
    assertThatThrownBy(() -> ledger.reverse(UUID.randomUUID(), topup.id(), "gastada", A))
        .extracting("code").isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void ledgerIsAppendOnly() {
    long id = newCustomer(1_000);
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entry SET note='x' WHERE customer_id=?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE customer_id=?", id))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void balanceAlwaysEqualsLedgerSum() {
    long id = newCustomer(10_000);
    var rnd = new java.util.Random(42);
    for (int i = 0; i < 100; i++) {
      long cents = 1 + rnd.nextInt(2_000);
      try {
        if (rnd.nextBoolean()) ledger.topUp(UUID.randomUUID(), id, cents, null, A);
        else ledger.spend(UUID.randomUUID(), id, cents, null, A);
      } catch (BusinessException ignored) {
      }
    }
    long sum = jdbc.queryForObject("SELECT SUM(amount_cents) FROM ledger_entry WHERE customer_id=?", Long.class, id);
    assertThat(balance(id)).isEqualTo(sum);
  }

  @Test
  void refundAndAdjustRequireNote() {
    long id = newCustomer(2_000);
    assertThatThrownBy(() -> ledger.refund(UUID.randomUUID(), id, 500, " ", A))
        .extracting("code").isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> ledger.adjust(UUID.randomUUID(), id, 0, "x", A))
        .extracting("code").isEqualTo("VALIDATION_ERROR");
  }
}
