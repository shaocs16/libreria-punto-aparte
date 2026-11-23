package com.papeleria.ledger;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerQueries {
  private final JdbcTemplate read;

  public LedgerQueries(@Qualifier("readJdbc") JdbcTemplate read) { this.read = read; }

  /** from/to ya en formato de BD (ver TimeRange). */
  public record Filter(Long customerId, String from, String to, EntryType type,
                       Long min, Long max, String actor) {}

  public record Totals(long inCents, long outCents) {}

  public record Result(List<LedgerEntry> items, long total, int page, int size, Totals totals) {}

  /** Cláusula WHERE sobre ledger_entry con alias {@code e}. */
  public record WhereClause(String sql, List<Object> args) {}

  public static WhereClause where(Filter f) {
    var sb = new StringBuilder(" WHERE 1=1");
    var args = new ArrayList<Object>();
    if (f.customerId() != null) { sb.append(" AND e.customer_id = ?"); args.add(f.customerId()); }
    if (f.from() != null) { sb.append(" AND e.created_at >= ?"); args.add(f.from()); }
    if (f.to() != null) { sb.append(" AND e.created_at < ?"); args.add(f.to()); }
    if (f.type() != null) { sb.append(" AND e.type = ?"); args.add(f.type().name()); }
    if (f.min() != null) { sb.append(" AND abs(e.amount_cents) >= ?"); args.add(f.min()); }
    if (f.max() != null) { sb.append(" AND abs(e.amount_cents) <= ?"); args.add(f.max()); }
    if (f.actor() != null && !f.actor().isBlank()) {
      sb.append(" AND (e.actor_login = ? OR e.actor_name = ?)");
      args.add(f.actor());
      args.add(f.actor());
    }
    return new WhereClause(sb.toString(), args);
  }

  public Result search(Filter f, int page, int size) {
    size = Math.min(Math.max(size, 1), 200);
    page = Math.max(page, 0);
    var w = where(f);
    var totals = read.queryForObject(
        "SELECT COUNT(*), COALESCE(SUM(CASE WHEN e.amount_cents>0 THEN e.amount_cents END),0),"
            + " COALESCE(-SUM(CASE WHEN e.amount_cents<0 THEN e.amount_cents END),0)"
            + " FROM ledger_entry e" + w.sql(),
        (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}, w.args().toArray());
    var pageArgs = new ArrayList<>(w.args());
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var items = read.query("SELECT e.* FROM ledger_entry e" + w.sql()
        + " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?", LedgerService.MAPPER, pageArgs.toArray());
    return new Result(items, totals[0], page, size, new Totals(totals[1], totals[2]));
  }
}
