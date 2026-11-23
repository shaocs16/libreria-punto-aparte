package com.papeleria.customer;

import com.papeleria.common.BusinessException;
import com.papeleria.common.RequestContext;
import com.papeleria.security.RequiresPin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
  private final CustomerService service;

  public CustomerController(CustomerService service) { this.service = service; }

  public record CustomerReq(
      @Size(max = 40) String code,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 30) String phone,
      @Size(max = 30) String document,
      @Size(max = 1000) String notes) {
    CustomerService.Input toInput() { return new CustomerService.Input(code, name, phone, document, notes); }
  }

  @GetMapping
  public CustomerService.Page list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(defaultValue = "false") boolean lowBalance,
                                   @RequestParam(required = false) Integer inactiveDays,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size,
                                   @RequestParam(required = false) String sort) {
    return service.list(q, active, lowBalance, inactiveDays, page, size, sort);
  }

  @GetMapping("/suggest")
  public List<Map<String, Object>> suggest(@RequestParam String q) {
    return service.suggest(q);
  }

  @PostMapping
  public ResponseEntity<Customer> create(@RequestBody @Valid CustomerReq r) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r.toInput(), RequestContext.actor()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Customer> get(@PathVariable long id) {
    var c = service.get(id);
    return ResponseEntity.ok(c);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Customer> update(@PathVariable long id, @RequestHeader("If-Match") String ifMatch,
                                         @RequestBody @Valid CustomerReq r) {
    long version;
    try {
      version = Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").strip());
    } catch (NumberFormatException e) {
      throw BusinessException.validation("If-Match debe ser la versión del cliente");
    }
    var c = service.update(id, version, r.toInput(), RequestContext.actor());
    return ResponseEntity.ok(c);
  }

  @RequiresPin
  @PostMapping("/{id}/deactivate")
  public Customer deactivate(@PathVariable long id) {
    return service.setActive(id, false, RequestContext.actor());
  }

  @RequiresPin
  @PostMapping("/{id}/reactivate")
  public Customer reactivate(@PathVariable long id) {
    return service.setActive(id, true, RequestContext.actor());
  }
}
