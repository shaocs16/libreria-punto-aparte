package com.papeleria.export;

import com.papeleria.customer.CustomerService;
import com.papeleria.ledger.LedgerService;
import com.papeleria.settings.SettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class ReceiptController {
  private final LedgerService ledger;
  private final CustomerService customers;
  private final SettingsService settings;

  public ReceiptController(LedgerService ledger, CustomerService customers, SettingsService settings) {
    this.ledger = ledger;
    this.customers = customers;
    this.settings = settings;
  }

  @GetMapping("/{id}/receipt")
  public Map<String, Object> receipt(@PathVariable long id) {
    var e = ledger.findById(id);
    var c = customers.get(e.customerId());
    var s = settings.all();
    var out = new LinkedHashMap<String, Object>();
    out.put("businessName", s.get("business_name"));
    out.put("footer", s.get("ticket_footer"));
    out.put("entry", e);
    out.put("customer", Map.of("id", c.id(), "code", c.code(), "name", c.name()));
    return out;
  }
}
