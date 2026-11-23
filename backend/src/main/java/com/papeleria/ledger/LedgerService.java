package com.papeleria.ledger;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import com.papeleria.common.Source;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LedgerService {
  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public LedgerService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public record Cmd(UUID key, long customerId, EntryType type, long signedCents,
                    String note, Source source, Long reversesId, String importJobId) {}

  public record Result(LedgerEntry entry, boolean replay) {}

  static final RowMapper<LedgerEntry> MAPPER = (rs, i) -> new LedgerEntry(
      rs.getLong("id"), rs.getLong("customer_id"), EntryType.valueOf(rs.getString("type")),
      rs.getLong("amount_cents"), rs.getLong("balance_after_cents"),
      rs.getObject("reverses_entry_id") == null ? null : rs.getLong("reverses_entry_id"),
      rs.getString("note"), Source.valueOf(rs.getString("source")),
      rs.getString("actor_name"), rs.getString("actor_login"), rs.getString("created_at"),
      rs.getString("idempotency_key"));

  @Transactional
  public Result apply(Cmd c, Actor actor) {
    var existing = findByKey(c.key());
    if (existing.isPresent()) {
      var e = existing.get();
      if (e.customerId() != c.customerId() || e.amountCents() != c.signedCents() || e.type() != c.type()) {
        throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
            "La Idempotency-Key ya se usó con una operación distinta");
      }
      return new Result(e, true);
    }
    var active = jdbc.query("SELECT active FROM customer WHERE id = ?",
        (rs, i) -> rs.getInt(1), c.customerId());
    if (active.isEmpty()) throw BusinessException.notFound("Cliente");
    if (active.get(0) == 0) {
      throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_INACTIVE",
          "El cliente está de baja");
    }

    Long newBalance = jdbc.query("""
        UPDATE customer_balance
           SET balance_cents = balance_cents + ?, version = version + 1,
               last_entry_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
               updated_at    = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE customer_id = ? AND balance_cents + ? >= 0
        RETURNING balance_cents
        """, rs -> rs.next() ? rs.getLong(1) : null,
        c.signedCents(), c.customerId(), c.signedCents());
    if (newBalance == null) {
      throw BusinessException.conflict("INSUFFICIENT_BALANCE", "Saldo insuficiente");
    }

    Long id = jdbc.queryForObject("""
        INSERT INTO ledger_entry(customer_id,type,amount_cents,balance_after_cents,
                                 idempotency_key,reverses_entry_id,note,source,import_job_id,actor_login,actor_name)
        VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
        """, Long.class, c.customerId(), c.type().name(), c.signedCents(), newBalance,
        c.key().toString(), c.reversesId(), c.note(), c.source().name(), c.importJobId(),
        actor.login(), actor.name());

    audit.record("LEDGER_" + c.type(), "ledger_entry", id, null,
        Map.of("customerId", c.customerId(), "amountCents", c.signedCents(), "balanceAfter", newBalance),
        actor);
    return new Result(findById(id), false);
  }

  /**
   * Ruta ligera para importaciones: mismas reglas (cliente activo, saldo >= 0, ledger append-only) pero sin
   * lectura previa ni auditoría por fila (el job deja un único IMPORT_EXECUTED). La unicidad de la clave
   * de idempotencia la garantiza la BD.
   */
  public void applyBulk(Cmd c, Actor actor) {
    Long newBalance = jdbc.query("""
        UPDATE customer_balance
           SET balance_cents = balance_cents + ?, version = version + 1,
               last_entry_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
               updated_at    = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE customer_id = ? AND balance_cents + ? >= 0
           AND EXISTS (SELECT 1 FROM customer WHERE id = ? AND active = 1)
        RETURNING balance_cents
        """, rs -> rs.next() ? rs.getLong(1) : null,
        c.signedCents(), c.customerId(), c.signedCents(), c.customerId());
    if (newBalance == null) {
      var active = jdbc.query("SELECT active FROM customer WHERE id = ?", (rs, i) -> rs.getInt(1), c.customerId());
      if (active.isEmpty()) throw BusinessException.notFound("Cliente");
      if (active.get(0) == 0) {
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_INACTIVE", "El cliente está de baja");
      }
      throw BusinessException.conflict("INSUFFICIENT_BALANCE", "Saldo insuficiente");
    }
    jdbc.update("""
        INSERT INTO ledger_entry(customer_id,type,amount_cents,balance_after_cents,
                                 idempotency_key,reverses_entry_id,note,source,import_job_id,actor_login,actor_name)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, c.customerId(), c.type().name(), c.signedCents(), newBalance, c.key().toString(),
        c.reversesId(), c.note(), c.source().name(), c.importJobId(), actor.login(), actor.name());
  }

  public record BulkMove(UUID key, long customerId, EntryType type, long signedCents, String note) {}

  /**
   * Inserción masiva de movimientos (importaciones) dentro de la transacción de escritura ya abierta:
   * saldos calculados en memoria bajo el bloqueo de escritura, INSERT en lote y un UPDATE por cliente.
   * Mismas reglas que apply(): cliente activo y saldo nunca negativo.
   */
  public void applyBulkBatch(List<BulkMove> moves, String importJobId, Actor actor) {
    record State(boolean active, long balance) {}
    Map<Long, State> state = new java.util.HashMap<>();
    jdbc.query("""
        SELECT c.id, c.active, b.balance_cents FROM customer c JOIN customer_balance b ON b.customer_id = c.id
        """, rs -> {
      state.put(rs.getLong(1), new State(rs.getInt(2) == 1, rs.getLong(3)));
    });
    Map<Long, Long> running = new java.util.LinkedHashMap<>();
    List<Object[]> inserts = new java.util.ArrayList<>(moves.size());
    for (BulkMove m : moves) {
      State st = state.get(m.customerId());
      if (st == null) throw BusinessException.notFound("Cliente");
      if (!st.active()) {
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_INACTIVE", "El cliente está de baja");
      }
      long next = running.getOrDefault(m.customerId(), st.balance()) + m.signedCents();
      if (next < 0) throw BusinessException.conflict("INSUFFICIENT_BALANCE", "Saldo insuficiente");
      running.put(m.customerId(), next);
      inserts.add(new Object[] {m.customerId(), m.type().name(), m.signedCents(), next, m.key().toString(),
          m.note(), Source.IMPORT.name(), importJobId, actor.login(), actor.name()});
    }
    String insert = """
        INSERT INTO ledger_entry(customer_id,type,amount_cents,balance_after_cents,idempotency_key,
                                 note,source,import_job_id,actor_login,actor_name)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """;
    for (int i = 0; i < inserts.size(); i += 5000) {
      jdbc.batchUpdate(insert, inserts.subList(i, Math.min(i + 5000, inserts.size())));
    }
    List<Object[]> updates = new java.util.ArrayList<>(running.size());
    running.forEach((id, bal) -> updates.add(new Object[] {bal, id}));
    jdbc.batchUpdate("""
        UPDATE customer_balance
           SET balance_cents = ?, version = version + 1,
               last_entry_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
               updated_at    = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE customer_id = ?
        """, updates);
  }

  public Result topUp(UUID k, long cust, long cents, String note, Actor a) {
    requirePositive(cents);
    return apply(new Cmd(k, cust, EntryType.TOPUP, cents, note, Source.UI, null, null), a);
  }

  public Result spend(UUID k, long cust, long cents, String note, Actor a) {
    requirePositive(cents);
    return apply(new Cmd(k, cust, EntryType.SPEND, -cents, note, Source.UI, null, null), a);
  }

  public Result refund(UUID k, long cust, long cents, String note, Actor a) {
    requirePositive(cents);
    requireNote(note);
    return apply(new Cmd(k, cust, EntryType.REFUND, -cents, note, Source.UI, null, null), a);
  }

  public Result adjust(UUID k, long cust, long signedCents, String note, Actor a) {
    if (signedCents == 0) throw BusinessException.validation("El ajuste no puede ser 0");
    requireNote(note);
    return apply(new Cmd(k, cust, EntryType.ADJUST, signedCents, note, Source.UI, null, null), a);
  }

  @Transactional
  public Result reverse(UUID k, long entryId, String reason, Actor a) {
    requireNote(reason);
    var replay = findByKey(k);
    if (replay.isPresent()) {
      var e = replay.get();
      if (e.type() != EntryType.REVERSAL || e.reversesEntryId() == null || e.reversesEntryId() != entryId) {
        throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
            "La Idempotency-Key ya se usó con una operación distinta");
      }
      return new Result(e, true);
    }
    var orig = findByIdOpt(entryId).orElseThrow(() -> BusinessException.notFound("Movimiento"));
    if (orig.type() == EntryType.REVERSAL) {
      throw BusinessException.conflict("ALREADY_A_REVERSAL", "No se puede anular una anulación");
    }
    Integer n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_entry WHERE reverses_entry_id = ?", Integer.class, entryId);
    if (n != null && n > 0) {
      throw BusinessException.conflict("ALREADY_REVERSED", "El movimiento ya está anulado");
    }
    return apply(new Cmd(k, orig.customerId(), EntryType.REVERSAL, -orig.amountCents(), reason,
        Source.UI, orig.id(), null), a);
  }

  public Optional<LedgerEntry> findByKey(UUID key) {
    return jdbc.query("SELECT * FROM ledger_entry WHERE idempotency_key = ?", MAPPER, key.toString())
        .stream().findFirst();
  }

  public Optional<LedgerEntry> findByIdOpt(long id) {
    return jdbc.query("SELECT * FROM ledger_entry WHERE id = ?", MAPPER, id).stream().findFirst();
  }

  public LedgerEntry findById(long id) {
    return findByIdOpt(id).orElseThrow(() -> BusinessException.notFound("Movimiento"));
  }

  private static void requirePositive(long cents) {
    if (cents <= 0) throw BusinessException.validation("El importe debe ser mayor que 0");
  }

  private static void requireNote(String note) {
    if (note == null || note.isBlank()) throw BusinessException.validation("La nota/motivo es obligatoria");
  }

  public record Page(List<LedgerEntry> items, long total, int page, int size) {}
}

