package com.papeleria.ledger;

import com.papeleria.common.BusinessException;
import com.papeleria.common.RequestContext;
import com.papeleria.common.TimeRange;
import com.papeleria.security.RequiresPin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LedgerController {
  private final LedgerService ledger;
  private final LedgerQueries queries;

  public LedgerController(LedgerService ledger, LedgerQueries queries) {
    this.ledger = ledger;
    this.queries = queries;
  }

  public record MoneyReq(@Positive long amountCents, @Size(max = 500) String note) {}
  public record NoteMoneyReq(@Positive long amountCents, @NotBlank @Size(max = 500) String note) {}
  public record AdjustReq(long amountCents, @NotBlank @Size(max = 500) String note) {}
  public record ReasonReq(@NotBlank @Size(max = 500) String reason) {}

  @PostMapping("/customers/{id}/topups")
  public ResponseEntity<LedgerEntry> topUp(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                                           @RequestBody @Valid MoneyReq r) {
    return respond(ledger.topUp(uuid(key), id, r.amountCents(), r.note(), RequestContext.actor()));
  }

  @PostMapping("/customers/{id}/spends")
  public ResponseEntity<LedgerEntry> spend(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                                           @RequestBody @Valid MoneyReq r) {
    return respond(ledger.spend(uuid(key), id, r.amountCents(), r.note(), RequestContext.actor()));
  }

  @RequiresPin
  @PostMapping("/customers/{id}/refunds")
  public ResponseEntity<LedgerEntry> refund(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                                            @RequestBody @Valid NoteMoneyReq r) {
    return respond(ledger.refund(uuid(key), id, r.amountCents(), r.note(), RequestContext.actor()));
  }

  @RequiresPin
  @PostMapping("/customers/{id}/adjustments")
  public ResponseEntity<LedgerEntry> adjust(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                                            @RequestBody @Valid AdjustReq r) {
    return respond(ledger.adjust(uuid(key), id, r.amountCents(), r.note(), RequestContext.actor()));
  }

  @RequiresPin
  @PostMapping("/ledger/{entryId}/reversal")
  public ResponseEntity<LedgerEntry> reverse(@PathVariable long entryId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @RequestBody @Valid ReasonReq r) {
    return respond(ledger.reverse(uuid(key), entryId, r.reason(), RequestContext.actor()));
  }

  @GetMapping("/ledger")
  public LedgerQueries.Result list(@RequestParam(required = false) Long customerId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) EntryType type,
                                   @RequestParam(required = false) Long min,
                                   @RequestParam(required = false) Long max,
                                   @RequestParam(required = false) String actor,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
    return queries.search(new LedgerQueries.Filter(customerId, TimeRange.from(from), TimeRange.to(to),
        type, min, max, actor), page, size);
  }

  @GetMapping("/ledger/{id}")
  public LedgerEntry get(@PathVariable long id) {
    return ledger.findById(id);
  }

  private static ResponseEntity<LedgerEntry> respond(LedgerService.Result r) {
    var b = ResponseEntity.status(r.replay() ? HttpStatus.OK : HttpStatus.CREATED);
    if (r.replay()) b.header("Idempotent-Replay", "true");
    return b.body(r.entry());
  }

  private static UUID uuid(String key) {
    try {
      return UUID.fromString(key);
    } catch (IllegalArgumentException e) {
      throw BusinessException.validation("Idempotency-Key debe ser un UUID");
    }
  }
}
