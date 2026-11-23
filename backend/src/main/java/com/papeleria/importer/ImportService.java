package com.papeleria.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import com.papeleria.common.Ids;
import com.papeleria.common.Money;
import com.papeleria.common.Source;
import com.papeleria.customer.CustomerService;
import com.papeleria.export.CsvSink;
import com.papeleria.ledger.EntryType;
import com.papeleria.ledger.LedgerService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportService {
  public static final long MAX_BYTES = 50L * 1024 * 1024;
  static final int MAX_ROWS = 100_000;
  private static final Pattern PHONE = Pattern.compile("[0-9+()\\-. ]{6,30}");
  private static final Pattern DOCUMENT = Pattern.compile("[A-Za-z0-9\\-./]{3,30}");
  private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, String>>> ERRORS = new TypeReference<>() {};

  public enum Kind {
    CUSTOMERS(List.of("code", "name", "phone", "document", "notes", "initial_balance"), Set.of("code", "name")),
    MOVEMENTS(List.of("row_id", "customer_code", "type", "amount", "note"),
        Set.of("customer_code", "type", "amount"));

    final List<String> columns;
    final Set<String> required;

    Kind(List<String> columns, Set<String> required) {
      this.columns = columns;
      this.required = required;
    }

    public List<String> columns() { return columns; }

    public static Kind parse(String s) {
      try {
        return valueOf(s == null ? "" : s.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw BusinessException.validation("kind debe ser CUSTOMERS o MOVEMENTS");
      }
    }
  }

  private static final Map<String, String> ALIASES = Map.ofEntries(
      Map.entry("codigo", "code"), Map.entry("nombre", "name"), Map.entry("telefono", "phone"),
      Map.entry("documento", "document"), Map.entry("dni", "document"), Map.entry("nif", "document"),
      Map.entry("notas", "notes"), Map.entry("saldo_inicial", "initial_balance"),
      Map.entry("saldo", "initial_balance"), Map.entry("codigo_cliente", "customer_code"),
      Map.entry("cliente", "customer_code"), Map.entry("tipo", "type"), Map.entry("importe", "amount"),
      Map.entry("nota", "note"));

  static final class RowData {
    final int num;
    final Map<String, String> values;
    final List<Map<String, String>> errors = new ArrayList<>();

    RowData(int num, Map<String, String> values) {
      this.num = num;
      this.values = values;
    }

    void err(String column, String message) { errors.add(Map.of("column", column, "message", message)); }
  }

  private final JdbcTemplate jdbc;
  private final JdbcTemplate read;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;
  private final CustomerService customers;
  private final LedgerService ledger;
  private final AuditService audit;

  public ImportService(JdbcTemplate jdbc, @Qualifier("readJdbc") JdbcTemplate read,
                       PlatformTransactionManager tm, ObjectMapper mapper, CustomerService customers,
                       LedgerService ledger, AuditService audit) {
    this.jdbc = jdbc;
    this.read = read;
    this.tx = new TransactionTemplate(tm);
    this.mapper = mapper;
    this.customers = customers;
    this.ledger = ledger;
    this.audit = audit;
  }

  // ---------------------------------------------------------------- subida y validación

  public Map<String, Object> upload(String kindStr, MultipartFile file, Actor actor) throws IOException {
    Kind kind = Kind.parse(kindStr);
    if (file == null || file.isEmpty()) throw BusinessException.validation("Fichero vacío");
    if (file.getSize() > MAX_BYTES) throw fileTooLarge();
    String name = safeName(file.getOriginalFilename());
    String lower = name.toLowerCase(Locale.ROOT);
    boolean xlsx = lower.endsWith(".xlsx");
    if (!xlsx && !lower.endsWith(".csv")) {
      throw BusinessException.validation("Solo se admiten ficheros .csv y .xlsx");
    }
    Path tmp = Files.createTempFile("import-", xlsx ? ".xlsx" : ".csv");
    try {
      file.transferTo(tmp);
      checkMagic(tmp, xlsx);
      String sha = sha256(tmp);
      if (alreadyExecuted(kind, sha)) {
        throw BusinessException.conflict("FILE_ALREADY_IMPORTED", "Este fichero ya fue importado");
      }
      List<RowData> rows = parse(tmp, xlsx, kind);
      String jobId = UUID.randomUUID().toString();
      tx.executeWithoutResult(st -> {
        if (kind == Kind.CUSTOMERS) validateCustomers(rows); else validateMovements(rows);
        saveJob(jobId, kind, name, sha, rows, actor);
      });
      return preview(jobId);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private boolean alreadyExecuted(Kind kind, String sha) {
    Integer n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM import_job WHERE kind = ? AND file_sha256 = ? AND status = 'EXECUTED'",
        Integer.class, kind.name(), sha);
    return n != null && n > 0;
  }

  private static BusinessException fileTooLarge() {
    return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El fichero supera 50 MB");
  }

  private static String safeName(String original) {
    String n = original == null ? "import" : original;
    n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
    return n.length() > 120 ? n.substring(n.length() - 120) : n;
  }

  private static void checkMagic(Path f, boolean xlsx) throws IOException {
    byte[] head = new byte[4096];
    int n;
    try (InputStream in = Files.newInputStream(f)) { n = in.readNBytes(head, 0, head.length); }
    boolean zip = n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    if (xlsx && !zip) throw BusinessException.validation("El fichero no es un XLSX válido");
    if (!xlsx) {
      if (zip) throw BusinessException.validation("El fichero no es un CSV de texto");
      for (int i = 0; i < n; i++) {
        if (head[i] == 0) throw BusinessException.validation("El fichero no es un CSV de texto");
      }
    }
  }

  private static String sha256(Path f) throws IOException {
    try (InputStream in = Files.newInputStream(f)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
      return java.util.HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String normalizeHeader(String h) {
    String n = CustomerService.normalize(h).strip().replace(' ', '_').replace('-', '_');
    return ALIASES.getOrDefault(n, n);
  }

  private List<RowData> parse(Path file, boolean xlsx, Kind kind) throws IOException {
    var colMap = new AtomicReference<Map<Integer, String>>();
    var rows = new ArrayList<RowData>();
    TabularParser parser = xlsx ? new XlsxParser() : new CsvParser();
    parser.parse(file, (num, cells) -> {
      if (colMap.get() == null) {
        var m = new HashMap<Integer, String>();
        for (int i = 0; i < cells.size(); i++) {
          String h = normalizeHeader(cells.get(i));
          if (kind.columns.contains(h) && !m.containsValue(h)) m.put(i, h);
        }
        for (String req : kind.required) {
          if (!m.containsValue(req)) throw BusinessException.validation("Falta la columna obligatoria: " + req);
        }
        colMap.set(m);
        return;
      }
      if (rows.size() >= MAX_ROWS) throw BusinessException.validation("El fichero supera " + MAX_ROWS + " filas");
      var values = new LinkedHashMap<String, String>();
      for (String c : kind.columns) values.put(c, "");
      colMap.get().forEach((idx, col) -> values.put(col, idx < cells.size() ? cells.get(idx) : ""));
      rows.add(new RowData(num, values));
    });
    if (colMap.get() == null) throw BusinessException.validation("Fichero sin cabecera");
    if (rows.isEmpty()) throw BusinessException.validation("El fichero no tiene filas de datos");
    return rows;
  }

  private void validateCustomers(List<RowData> rows) {
    Set<String> codes = new HashSet<>(jdbc.queryForList("SELECT code FROM customer", String.class));
    Set<String> docs = new HashSet<>(
        jdbc.queryForList("SELECT document FROM customer WHERE document IS NOT NULL", String.class));
    for (RowData r : rows) {
      var v = r.values;
      String code = v.get("code"), name = v.get("name"), phone = v.get("phone"), doc = v.get("document");
      if (code.isEmpty()) r.err("code", "obligatorio");
      else if (code.length() > 40) r.err("code", "máximo 40 caracteres");
      else if (!codes.add(code)) r.err("code", "código duplicado (en el fichero o ya existe)");
      if (name.isEmpty()) r.err("name", "obligatorio");
      else if (name.length() > 200) r.err("name", "máximo 200 caracteres");
      if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) r.err("phone", "formato de teléfono no válido");
      if (!doc.isEmpty()) {
        if (!DOCUMENT.matcher(doc).matches()) r.err("document", "formato de documento no válido");
        else if (!docs.add(doc)) r.err("document", "documento duplicado (en el fichero o ya existe)");
      }
      if (v.get("notes").length() > 1000) r.err("notes", "máximo 1000 caracteres");
      String bal = v.get("initial_balance");
      if (!bal.isEmpty()) {
        try {
          long cents = Money.parse(bal);
          if (cents < 0) r.err("initial_balance", "no puede ser negativo");
          else v.put("initial_cents", String.valueOf(cents));
        } catch (IllegalArgumentException e) {
          r.err("initial_balance", e.getMessage());
        }
      }
    }
  }

  private record Cust(long id, boolean active, long balance) {}

  private void validateMovements(List<RowData> rows) {
    Map<String, Cust> byCode = new HashMap<>();
    jdbc.query("""
        SELECT c.code, c.id, c.active, b.balance_cents
          FROM customer c JOIN customer_balance b ON b.customer_id = c.id
        """, rs -> {
      byCode.put(rs.getString(1), new Cust(rs.getLong(2), rs.getInt(3) == 1, rs.getLong(4)));
    });
    Map<Long, Long> running = new HashMap<>();
    Set<String> rowIds = new HashSet<>();
    for (RowData r : rows) {
      var v = r.values;
      Cust cust = null;
      String code = v.get("customer_code");
      if (code.isEmpty()) r.err("customer_code", "obligatorio");
      else {
        cust = byCode.get(code);
        if (cust == null) r.err("customer_code", "el cliente no existe");
        else if (!cust.active()) r.err("customer_code", "el cliente está de baja");
      }
      String type = v.get("type").toUpperCase(Locale.ROOT);
      if (!type.equals("TOPUP") && !type.equals("SPEND")) r.err("type", "debe ser TOPUP o SPEND");
      else v.put("type", type);
      long cents = 0;
      try {
        cents = Money.parse(v.get("amount"));
        if (cents <= 0) r.err("amount", "debe ser mayor que 0");
        else v.put("amount_cents", String.valueOf(cents));
      } catch (IllegalArgumentException e) {
        r.err("amount", e.getMessage());
      }
      String rowId = v.get("row_id");
      if (!rowId.isEmpty()) {
        if (rowId.length() > 100) r.err("row_id", "máximo 100 caracteres");
        else if (!rowIds.add(rowId)) r.err("row_id", "row_id repetido en el fichero");
        else {
          Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE idempotency_key = ?",
              Integer.class, Ids.v5("movement-row:" + rowId).toString());
          if (n != null && n > 0) r.err("row_id", "ya fue importado anteriormente");
        }
      }
      if (v.get("note").length() > 500) r.err("note", "máximo 500 caracteres");
      if (r.errors.isEmpty() && cust != null) {
        long signed = type.equals("TOPUP") ? cents : -cents;
        long next = running.getOrDefault(cust.id(), cust.balance()) + signed;
        if (next < 0) r.err("amount", "saldo insuficiente (saldo acumulado: " + Money.format(next) + ")");
        else running.put(cust.id(), next);
      }
    }
  }

  private void saveJob(String jobId, Kind kind, String name, String sha, List<RowData> rows, Actor actor) {
    int bad = (int) rows.stream().filter(r -> !r.errors.isEmpty()).count();
    jdbc.update("""
        INSERT INTO import_job(id, kind, file_name, file_sha256, status, total_rows, ok_rows, error_rows, actor_login)
        VALUES (?,?,?,?, 'VALIDATED', ?,?,?,?)
        """, jobId, kind.name(), name, sha, rows.size(), rows.size() - bad, bad, actor.login());
    jdbc.batchUpdate("INSERT INTO import_row(job_id, row_num, raw, error) VALUES (?,?,?,?)", rows, 500,
        (ps, r) -> {
          ps.setString(1, jobId);
          ps.setInt(2, r.num);
          ps.setString(3, json(r.values));
          ps.setString(4, r.errors.isEmpty() ? null : json(r.errors));
        });
  }

  private String json(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------- consulta

  public Map<String, Object> preview(String jobId) {
    var job = read.queryForList("""
        SELECT id, kind, file_name AS fileName, status, total_rows AS totalRows, ok_rows AS okRows,
               error_rows AS errorRows, created_at AS createdAt, executed_at AS executedAt
          FROM import_job WHERE id = ?
        """, jobId);
    if (job.isEmpty()) throw BusinessException.notFound("Importación");
    var out = new LinkedHashMap<String, Object>(job.get(0));
    out.put("errors", read.query("""
        SELECT row_num, raw, error FROM import_row WHERE job_id = ? AND error IS NOT NULL
         ORDER BY row_num LIMIT 200
        """, (rs, i) -> rowView(rs.getInt(1), rs.getString(2), rs.getString(3)), jobId));
    out.put("sample", read.query("""
        SELECT row_num, raw, error FROM import_row WHERE job_id = ? ORDER BY row_num LIMIT 20
        """, (rs, i) -> rowView(rs.getInt(1), rs.getString(2), rs.getString(3)), jobId));
    return out;
  }

  private Map<String, Object> rowView(int num, String raw, String error) {
    try {
      var m = new LinkedHashMap<String, Object>();
      m.put("row", num);
      m.put("values", mapper.readValue(raw, MAP));
      m.put("errors", error == null ? List.of() : mapper.readValue(error, ERRORS));
      return m;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public Kind kindOf(String jobId) {
    var k = read.queryForList("SELECT kind FROM import_job WHERE id = ?", String.class, jobId);
    if (k.isEmpty()) throw BusinessException.notFound("Importación");
    return Kind.valueOf(k.get(0));
  }

  public void writeErrorsCsv(String jobId, OutputStream out) throws IOException {
    kindOf(jobId);
    var sink = new CsvSink(out);
    sink.header(List.of("fila", "columna", "mensaje"));
    var rows = read.query("SELECT row_num, error FROM import_row WHERE job_id = ? AND error IS NOT NULL ORDER BY row_num",
        (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)}, jobId);
    for (Object[] r : rows) {
      for (var e : mapper.readValue((String) r[1], ERRORS)) {
        sink.row(List.of(r[0], e.get("column"), e.get("message")));
      }
    }
    sink.finish();
  }

  // ---------------------------------------------------------------- ejecución

  public Map<String, Object> confirm(String jobId, Actor actor) {
    try {
      tx.executeWithoutResult(st -> execute(jobId, actor));
    } catch (RuntimeException e) {
      jdbc.update("UPDATE import_job SET status = 'FAILED' WHERE id = ? AND status = 'VALIDATED'", jobId);
      throw e;
    }
    return preview(jobId);
  }

  private record RawRow(int num, String raw) {}

  private void execute(String jobId, Actor actor) {
    var jobs = jdbc.queryForList(
        "SELECT kind, file_sha256, status, error_rows FROM import_job WHERE id = ?", jobId);
    if (jobs.isEmpty()) throw BusinessException.notFound("Importación");
    var job = jobs.get(0);
    Kind kind = Kind.valueOf((String) job.get("kind"));
    String status = (String) job.get("status");
    if (!status.equals("VALIDATED")) {
      throw BusinessException.conflict("INVALID_STATE", "La importación no está pendiente de confirmar (" + status + ")");
    }
    if (((Number) job.get("error_rows")).intValue() > 0) {
      throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "IMPORT_HAS_ERRORS",
          "La importación tiene filas con errores; corrígelas y vuelve a subir el fichero");
    }
    if (alreadyExecuted(kind, (String) job.get("file_sha256"))) {
      throw BusinessException.conflict("FILE_ALREADY_IMPORTED", "Este fichero ya fue importado");
    }
    var rows = jdbc.query("SELECT row_num, raw FROM import_row WHERE job_id = ? ORDER BY row_num",
        (rs, i) -> new RawRow(rs.getInt(1), rs.getString(2)), jobId);
    if (kind == Kind.MOVEMENTS) {
      importMovements(jobId, rows, actor);
    } else {
      for (RawRow r : rows) importCustomer(jobId, r.num(), parseRow(r.raw()), actor);
    }
    jdbc.update("""
        UPDATE import_job SET status = 'EXECUTED', executed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE id = ? AND status = 'VALIDATED'
        """, jobId);
    audit.record("IMPORT_EXECUTED", "import_job", jobId, null, Map.of("kind", kind.name(), "rows", rows.size()), actor);
  }

  private void importCustomer(String jobId, int num, Map<String, String> v, Actor actor) {
    long id = customers.createBulk(new CustomerService.Input(v.get("code"), v.get("name"), v.get("phone"),
        v.get("document"), v.get("notes")));
    String cents = v.get("initial_cents");
    if (cents != null && !cents.isEmpty() && Long.parseLong(cents) > 0) {
      ledger.applyBulk(new LedgerService.Cmd(Ids.v5(jobId + ":" + num), id, EntryType.TOPUP,
          Long.parseLong(cents), "Saldo inicial importado", Source.IMPORT, null, jobId), actor);
    }
  }

  private Map<String, String> parseRow(String raw) {
    try {
      return mapper.readValue(raw, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void importMovements(String jobId, List<RawRow> rows, Actor actor) {
    Map<String, Long> ids = new HashMap<>();
    jdbc.query("SELECT code, id FROM customer", rs -> { ids.put(rs.getString(1), rs.getLong(2)); });
    var moves = new ArrayList<LedgerService.BulkMove>(rows.size());
    for (RawRow r : rows) {
      var v = parseRow(r.raw());
      long cents = Long.parseLong(v.get("amount_cents"));
      boolean topup = v.get("type").equals("TOPUP");
      String rowId = v.get("row_id");
      UUID key = rowId.isEmpty() ? Ids.v5(jobId + ":" + r.num()) : Ids.v5("movement-row:" + rowId);
      Long customerId = ids.get(v.get("customer_code"));
      if (customerId == null) throw BusinessException.notFound("Cliente " + v.get("customer_code"));
      moves.add(new LedgerService.BulkMove(key, customerId, topup ? EntryType.TOPUP : EntryType.SPEND,
          topup ? cents : -cents, v.get("note").isEmpty() ? null : v.get("note")));
    }
    ledger.applyBulkBatch(moves, jobId, actor);
  }

  public void discard(String jobId) {
    int n = jdbc.update("UPDATE import_job SET status = 'DISCARDED' WHERE id = ? AND status = 'VALIDATED'", jobId);
    if (n == 0) {
      kindOf(jobId);
      throw BusinessException.conflict("INVALID_STATE", "Solo se pueden descartar importaciones pendientes");
    }
    jdbc.update("DELETE FROM import_row WHERE job_id = ?", jobId);
  }
}
