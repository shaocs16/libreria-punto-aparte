package com.papeleria.export;

import com.papeleria.audit.AuditQueries;
import com.papeleria.common.BusinessException;
import com.papeleria.common.Money;
import com.papeleria.common.RequestContext;
import com.papeleria.common.TimeRange;
import com.papeleria.audit.AuditService;
import com.papeleria.ledger.EntryType;
import com.papeleria.ledger.LedgerQueries;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExportController {
  public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  public static final String CSV = "text/csv; charset=UTF-8";

  private final JdbcTemplate read;
  private final AuditQueries auditQueries;
  private final AuditService audit;

  public ExportController(@Qualifier("readJdbc") JdbcTemplate read, AuditQueries auditQueries, AuditService audit) {
    this.read = read;
    this.auditQueries = auditQueries;
    this.audit = audit;
  }

  /** Abre el sink adecuado según la extensión, tras fijar cabeceras. */
  public static TableSink sink(String format, String baseName, HttpServletResponse res) throws IOException {
    res.setHeader("Content-Disposition", "attachment; filename=\"" + baseName + "." + format + "\"");
    if (format.equals("xlsx")) {
      res.setContentType(XLSX);
      return new XlsxSink(res.getOutputStream());
    }
    res.setContentType(CSV);
    return new CsvSink(res.getOutputStream());
  }

  static String format(String file, String expectedBase, java.util.Set<String> allowed) {
    int dot = file.lastIndexOf('.');
    String ext = dot < 0 ? "" : file.substring(dot + 1).toLowerCase();
    if (!allowed.contains(ext)) throw BusinessException.notFound("Formato");
    return ext;
  }

  @GetMapping("/exports/{file}")
  public void export(@PathVariable String file,
                     @RequestParam(required = false) Long customerId,
                     @RequestParam(required = false) String from,
                     @RequestParam(required = false) String to,
                     @RequestParam(required = false) EntryType type,
                     @RequestParam(required = false) Long min,
                     @RequestParam(required = false) Long max,
                     @RequestParam(required = false) String actor,
                     @RequestParam(required = false) String entity,
                     @RequestParam(required = false) String id,
                     HttpServletResponse res) throws IOException {
    int dot = file.lastIndexOf('.');
    String name = dot < 0 ? file : file.substring(0, dot);
    String fmt = format(file, name, java.util.Set.of("csv", "xlsx"));
    String stamp = java.time.LocalDate.now().toString();
    switch (name) {
      case "customers" -> writeCustomers(fmt, "clientes-" + stamp, res);
      case "ledger" -> writeLedger(fmt, "movimientos-" + stamp, res, new LedgerQueries.Filter(
          customerId, TimeRange.from(from), TimeRange.to(to), type, min, max, actor));
      case "audit" -> writeAudit(fmt, "auditoria-" + stamp, res, new AuditQueries.Filter(
          entity, id, actor, TimeRange.from(from), TimeRange.to(to)));
      default -> throw BusinessException.notFound("Exportación");
    }
    audit.record("EXPORT", "export", name, null, Map.of("format", fmt), RequestContext.actor());
  }

  private void writeCustomers(String fmt, String base, HttpServletResponse res) throws IOException {
    try (TableSink s = sink(fmt, base, res)) {
      s.header(List.of("Código", "Nombre", "Teléfono", "Documento", "Notas", "Activo", "Saldo (€)", "Alta"));
      stream("""
          SELECT c.code, c.name, c.phone, c.document, c.notes, c.active, b.balance_cents, c.created_at
            FROM customer c JOIN customer_balance b ON b.customer_id = c.id ORDER BY c.code
          """, List.of(), rs -> s.row(List.of(
          nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)),
          nz(rs.getString(5)), rs.getInt(6) == 1 ? "Sí" : "No", Money.toDecimal(rs.getLong(7)),
          nz(rs.getString(8)))));
      s.finish();
    }
  }

  private void writeLedger(String fmt, String base, HttpServletResponse res, LedgerQueries.Filter f)
      throws IOException {
    var w = LedgerQueries.where(f);
    try (TableSink s = sink(fmt, base, res)) {
      s.header(List.of("ID", "Fecha (UTC)", "Código cliente", "Cliente", "Tipo", "Importe (€)",
          "Saldo tras movimiento (€)", "Nota", "Origen", "Usuario", "Anula a"));
      stream("""
          SELECT e.id, e.created_at, c.code, c.name, e.type, e.amount_cents, e.balance_after_cents,
                 e.note, e.source, e.actor_name, e.reverses_entry_id
            FROM ledger_entry e JOIN customer c ON c.id = e.customer_id
          """ + w.sql() + " ORDER BY e.created_at, e.id", w.args(), rs -> s.row(List.of(
          rs.getLong(1), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)),
          Money.toDecimal(rs.getLong(6)), Money.toDecimal(rs.getLong(7)), nz(rs.getString(8)),
          nz(rs.getString(9)), nz(rs.getString(10)),
          rs.getObject(11) == null ? "" : String.valueOf(rs.getLong(11)))));
      s.finish();
    }
  }

  private void writeAudit(String fmt, String base, HttpServletResponse res, AuditQueries.Filter f)
      throws IOException {
    try (TableSink s = sink(fmt, base, res)) {
      s.header(List.of("ID", "Fecha (UTC)", "Acción", "Entidad", "ID entidad", "Valor anterior",
          "Valor nuevo", "Usuario", "Nombre"));
      auditQueries.stream(f, rs -> {
        try {
          s.row(List.of(rs.getLong(1), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)),
              nz(rs.getString(5)), nz(rs.getString(6)), nz(rs.getString(7)), nz(rs.getString(8)),
              nz(rs.getString(9))));
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      });
      s.finish();
    }
  }

  private static String nz(String s) { return s == null ? "" : s; }

  @FunctionalInterface
  interface RowWriter { void write(java.sql.ResultSet rs) throws SQLException, IOException; }

  private void stream(String sql, List<Object> args, RowWriter w) {
    read.query(sql, rs -> {
      try {
        w.write(rs);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }, new ArrayList<>(args).toArray());
  }
}
