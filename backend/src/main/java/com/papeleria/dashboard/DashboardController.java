package com.papeleria.dashboard;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardQueries queries;

  public DashboardController(DashboardQueries queries) { this.queries = queries; }

  @GetMapping("/summary")
  public Map<String, Object> summary(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
    return queries.summary(from, to);
  }
}
