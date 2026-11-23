package com.papeleria.settings;

import com.papeleria.common.RequestContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.papeleria.security.RequiresPin;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
  private final SettingsService settings;

  public SettingsController(SettingsService settings) { this.settings = settings; }

  @GetMapping
  public Map<String, String> get() { return settings.all(); }

  @RequiresPin
  @PutMapping
  public Map<String, String> put(@RequestBody Map<String, String> body) {
    return settings.update(body, RequestContext.actor());
  }
}
