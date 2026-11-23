package com.papeleria.audit;

import com.papeleria.common.TimeRange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
  private final AuditQueries queries;

  public AuditController(AuditQueries queries) { this.queries = queries; }

  @GetMapping
  public AuditQueries.Page list(@RequestParam(required = false) String entity,
                                @RequestParam(required = false) String id,
                                @RequestParam(required = false) String actor,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
    return queries.search(new AuditQueries.Filter(entity, id, actor, TimeRange.from(from), TimeRange.to(to)),
        page, size);
  }
}
