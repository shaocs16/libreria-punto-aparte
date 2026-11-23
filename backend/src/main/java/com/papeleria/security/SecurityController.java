package com.papeleria.security;

import com.papeleria.common.RequestContext;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1")
@Validated
public class SecurityController {
  private final PinService pins;

  public SecurityController(PinService pins) { this.pins = pins; }

  public record PinReq(@NotBlank String pin, String currentPin) {}

  @GetMapping("/me")
  public Map<String, Object> me() {
    var a = RequestContext.actor();
    return Map.of("login", a.login(), "name", a.name(), "pinSet", pins.isSet());
  }

  /** Crear (primer arranque) o cambiar (requiere currentPin) el PIN. */
  @PostMapping("/security/pin")
  public Map<String, Object> setPin(@RequestBody @jakarta.validation.Valid PinReq req) {
    pins.setPin(req.pin(), req.currentPin(), RequestContext.actor());
    return Map.of("pinSet", true);
  }
}
