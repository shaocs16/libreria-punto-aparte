package com.papeleria.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class AuditQueries {
  public static final String COLUMNS = "id, at, action, entity, entity_id AS entityId, old_value AS oldValue, "
      + "new_value AS newValue, actor_login AS actorLogin, actor_name AS actorName, host";

  private final JdbcTemplate read;

  public AuditQueries(@Qualifier("readJdbc") JdbcTemplate read) { this.read = read; }

  public record Filter(String entity, String id, String actor, String from, String to) {}

  public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

  private record Where(String sql, List<Object> args) {}

  private static Where where(Filter f) {
    var sb = new StringBuilder(" WHERE 1=1");
    var args = new ArrayList<Object>();
    if (f.entity() != null && !f.entity().isBlank()) { sb.append(" AND entity = ?"); args.add(f.entity()); }
    if (f.id() != null && !f.id().isBlank()) { sb.append(" AND entity_id = ?"); args.add(f.id()); }
    if (f.actor() != null && !f.actor().isBlank()) {
      sb.append(" AND (actor_login = ? OR actor_name = ?)");
      args.add(f.actor());
      args.add(f.actor());
    }
    if (f.from() != null) { sb.append(" AND at >= ?"); args.add(f.from()); }
    if (f.to() != null) { sb.append(" AND at < ?"); args.add(f.to()); }
    return new Where(sb.toString(), args);
  }

  public Page search(Filter f, int page, int size) {
    size = Math.min(Math.max(size, 1), 200);
    page = Math.max(page, 0);
    var w = where(f);
    Long total = read.queryForObject("SELECT COUNT(*) FROM audit_log" + w.sql(), Long.class, w.args().toArray());
    var a = new ArrayList<>(w.args());
    a.add(size);
    a.add((long) page * size);
    var items = read.queryForList("SELECT " + COLUMNS + " FROM audit_log" + w.sql()
        + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?", a.toArray());
    return new Page(items, total == null ? 0 : total, page, size);
  }

  public void stream(Filter f, RowCallbackHandler h) {
    var w = where(f);
    read.query("SELECT id, at, action, entity, entity_id, old_value, new_value, actor_login, actor_name, host"
        + " FROM audit_log" + w.sql() + " ORDER BY at DESC, id DESC", h, w.args().toArray());
  }
}
