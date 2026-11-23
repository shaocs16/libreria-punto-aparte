package com.papeleria.customer;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
  private static final String SELECT = """
      SELECT c.id, c.code, c.name, c.phone, c.document, c.notes, c.active, c.version,
             b.balance_cents, b.last_entry_at
        FROM customer c JOIN customer_balance b ON b.customer_id = c.id
      """;
  private static final RowMapper<Customer> MAPPER = (rs, i) -> new Customer(
      rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("phone"),
      rs.getString("document"), rs.getString("notes"), rs.getInt("active") == 1, rs.getLong("version"),
      rs.getLong("balance_cents"), rs.getString("last_entry_at"));

  private final JdbcTemplate jdbc;
  private final JdbcTemplate read;
  private final AuditService audit;

  public CustomerService(JdbcTemplate jdbc, @Qualifier("readJdbc") JdbcTemplate read, AuditService audit) {
    this.jdbc = jdbc;
    this.read = read;
    this.audit = audit;
  }

  public record Input(String code, String name, String phone, String document, String notes) {}

  public record Page(List<Customer> items, long total, int page, int size) {}

  public static String normalize(String s) {
    if (s == null) return "";
    return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String searchText(Input in) {
    return normalize(String.join(" ", in.code(), in.name(),
        in.phone() == null ? "" : in.phone(), in.document() == null ? "" : in.document())).strip();
  }

  /**
   * Siguiente código automático: "C" + número correlativo (C001, C002, ...). Parte del mayor código
   * con ese formato ya existente, así nunca choca con clientes importados o creados a mano.
   * Se ejecuta dentro de la transacción de escritura, que está serializada.
   */
  String nextCode() {
    Long max = jdbc.queryForObject("""
        SELECT MAX(CAST(SUBSTR(code, 2) AS INTEGER)) FROM customer
         WHERE code GLOB 'C[0-9]*' AND code NOT GLOB 'C*[^0-9]*' AND LENGTH(code) <= 10
        """, Long.class);
    return String.format("C%03d", (max == null ? 0 : max) + 1);
  }

  @Transactional
  public Customer create(Input requested, Actor actor) {
    boolean auto = requested.code() == null || requested.code().isBlank();
    var in = auto ? new Input(nextCode(), requested.name(), requested.phone(), requested.document(),
        requested.notes()) : requested;
    Long id = jdbc.queryForObject("""
        INSERT INTO customer(code,name,phone,document,notes,search_text) VALUES (?,?,?,?,?,?) RETURNING id
        """, Long.class, in.code().strip(), in.name().strip(), blankToNull(in.phone()),
        blankToNull(in.document()), blankToNull(in.notes()), searchText(in));
    var c = getForWrite(id);
    audit.record("CUSTOMER_CREATED", "customer", id, null, c, actor);
    return c;
  }

  /** Alta ligera para importaciones: sin auditoría por fila ni relectura. Devuelve el id. */
  @Transactional
  public long createBulk(Input in) {
    Long id = jdbc.queryForObject("""
        INSERT INTO customer(code,name,phone,document,notes,search_text) VALUES (?,?,?,?,?,?) RETURNING id
        """, Long.class, in.code().strip(), in.name().strip(), blankToNull(in.phone()),
        blankToNull(in.document()), blankToNull(in.notes()), searchText(in));
    return id;
  }

  @Transactional
  public Customer update(long id, long expectedVersion, Input in, Actor actor) {
    var old = getForWrite(id);
    // El código es inmutable: lo referencian importaciones, tickets y movimientos. Se admite vacío (se conserva)
    // o igual al actual; cualquier otro valor se rechaza.
    if (in.code() != null && !in.code().isBlank() && !in.code().strip().equals(old.code())) {
      throw BusinessException.validation("El código del cliente no se puede modificar");
    }
    in = new Input(old.code(), in.name(), in.phone(), in.document(), in.notes());
    int n = jdbc.update("""
        UPDATE customer SET code=?, name=?, phone=?, document=?, notes=?, search_text=?,
               version = version + 1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE id = ? AND version = ?
        """, in.code().strip(), in.name().strip(), blankToNull(in.phone()), blankToNull(in.document()),
        blankToNull(in.notes()), searchText(in), id, expectedVersion);
    if (n == 0) throw BusinessException.conflict("STALE_VERSION", "El cliente fue modificado por otra persona");
    var updated = getForWrite(id);
    audit.record("CUSTOMER_UPDATED", "customer", id, old, updated, actor);
    return updated;
  }

  @Transactional
  public Customer setActive(long id, boolean active, Actor actor) {
    var old = getForWrite(id);
    if (!active && old.balanceCents() > 0) {
      throw BusinessException.conflict("CUSTOMER_HAS_BALANCE", "El cliente tiene saldo; devuélvelo o ajústalo antes");
    }
    jdbc.update("""
        UPDATE customer SET active=?, version = version + 1,
               updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?
        """, active ? 1 : 0, id);
    var updated = getForWrite(id);
    audit.record(active ? "CUSTOMER_REACTIVATED" : "CUSTOMER_DEACTIVATED", "customer", id, old, updated, actor);
    return updated;
  }

  public Customer get(long id) {
    return read.query(SELECT + " WHERE c.id = ?", MAPPER, id).stream().findFirst()
        .orElseThrow(() -> BusinessException.notFound("Cliente"));
  }

  private Customer getForWrite(long id) {
    return jdbc.query(SELECT + " WHERE c.id = ?", MAPPER, id).stream().findFirst()
        .orElseThrow(() -> BusinessException.notFound("Cliente"));
  }

  public Page list(String q, Boolean active, boolean lowBalance, Integer inactiveDays,
                   int page, int size, String sort) {
    size = Math.min(Math.max(size, 1), 200);
    page = Math.max(page, 0);
    var where = new StringBuilder(" WHERE 1=1");
    var args = new ArrayList<Object>();
    if (active != null) { where.append(" AND c.active = ?"); args.add(active ? 1 : 0); }
    if (q != null && !q.isBlank()) appendSearch(where, args, q);
    if (lowBalance) {
      where.append(" AND b.balance_cents > 0 AND b.balance_cents <= "
          + "(SELECT CAST(value AS INTEGER) FROM app_setting WHERE key='low_balance_cents')");
    }
    if (inactiveDays != null) {
      where.append(" AND b.balance_cents > 0 AND (b.last_entry_at IS NULL OR b.last_entry_at < "
          + "strftime('%Y-%m-%dT%H:%M:%fZ','now', ?))");
      args.add("-" + inactiveDays + " days");
    }
    String order = switch (sort == null ? "" : sort) {
      case "balance" -> "b.balance_cents DESC";
      case "balanceAsc" -> "b.balance_cents ASC";
      case "recent" -> "b.last_entry_at DESC";
      case "code" -> "c.code ASC";
      default -> "c.name COLLATE NOCASE ASC";
    };
    Long total = read.queryForObject(
        "SELECT COUNT(*) FROM customer c JOIN customer_balance b ON b.customer_id = c.id" + where,
        Long.class, args.toArray());
    var pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var items = read.query(SELECT + where + " ORDER BY " + order + ", c.id LIMIT ? OFFSET ?",
        MAPPER, pageArgs.toArray());
    return new Page(items, total == null ? 0 : total, page, size);
  }

  /** >= 3 caracteres: FTS5 trigram (subcadena). 1-2 caracteres: prefijo de campo/palabra con LIKE. */
  private static void appendSearch(StringBuilder where, List<Object> args, String q) {
    String n = normalize(q.strip());
    if (n.isEmpty()) return;
    if (n.codePointCount(0, n.length()) >= 3) {
      where.append(" AND c.id IN (SELECT rowid FROM customer_fts WHERE customer_fts MATCH ?)");
      args.add("\"" + n.replace("\"", "\"\"") + "\"");
    } else {
      String esc = n.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
      where.append(" AND (c.search_text LIKE ? ESCAPE '\\' OR c.search_text LIKE ? ESCAPE '\\')");
      args.add(esc + "%");
      args.add("% " + esc + "%");
    }
  }

  public List<Map<String, Object>> suggest(String q) {
    if (q == null || q.isBlank()) return List.of();
    var where = new StringBuilder(" WHERE c.active = 1");
    var args = new ArrayList<Object>();
    appendSearch(where, args, q);
    args.add(normalize(q.strip()));
    args.add(q.strip());
    return read.queryForList("""
        SELECT c.id, c.code, c.name, c.phone, b.balance_cents AS balanceCents
          FROM customer c JOIN customer_balance b ON b.customer_id = c.id
        """ + where + """
         ORDER BY (lower(c.code) = ? OR c.phone = ?) DESC, c.name COLLATE NOCASE
         LIMIT 8
        """, args.toArray());
  }
}
