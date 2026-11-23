package com.papeleria.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papeleria.common.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void record(String action, String entity, Object entityId, Object oldValue, Object newValue, Actor actor) {
    jdbc.update("""
        INSERT INTO audit_log(action, entity, entity_id, old_value, new_value, actor_login, actor_name)
        VALUES (?,?,?,?,?,?,?)
        """, action, entity, entityId == null ? null : String.valueOf(entityId),
        json(oldValue), json(newValue), actor.login(), actor.name());
  }

  private String json(Object o) {
    if (o == null) return null;
    try {
      return mapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      return String.valueOf(o);
    }
  }
}
