package com.papeleria;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSecurityTest {
  static final String TAILSCALE_HOST = "pc-servidor.tail123.ts.net";
  static final Path DB;
  static {
    try {
      DB = Files.createTempDirectory("papeleria-web").resolve("web.db");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.db-path", () -> DB.toString());
    r.add("PAPELERIA_ALLOWED_HOSTS", () -> "localhost," + TAILSCALE_HOST + "," + TAILSCALE_HOST + ":443");
  }

  @Autowired org.springframework.web.context.WebApplicationContext ctx;
  MockMvc mvc;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(ctx)
        .defaultRequest(get("/").header("Host", "localhost"))
        .addFilters(ctx.getBean(com.papeleria.common.LocalOnlyFilter.class),
            ctx.getBean(com.papeleria.common.ActorFilter.class))
        .build();
  }

  @Test
  void foreignHostRejected() throws Exception {
    mvc.perform(get("/api/v1/me").header("Host", "evil.com"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_HOST"));
  }

  @Test
  void mutationNeedsCustomHeaderAndSameOrigin() throws Exception {
    mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/customers").header("X-Requested-With", "papeleria-ui")
            .header("Origin", "http://evil.com").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ORIGIN"));
  }

  @Test
  void tailscaleHeaderAcceptedFromLoopback() throws Exception {
    mvc.perform(get("/api/v1/me").header("Tailscale-User-Login", "laura@x.com")
            .header("Tailscale-User-Name", "Laura").with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }))
        .andExpect(jsonPath("$.name").value("Laura"));
    mvc.perform(get("/api/v1/me").header("Tailscale-User-Login", "laura@x.com")
            .with(r -> { r.setRemoteAddr("10.0.0.5"); return r; }))
        .andExpect(jsonPath("$.login").value(org.hamcrest.Matchers.startsWith("local@")));
  }

  @Test
  void tailscaleHttpsHostAndIdentityAccepted() throws Exception {
    for (String host : new String[] { TAILSCALE_HOST, TAILSCALE_HOST + ":443" }) {
      mvc.perform(get("/api/v1/me").header("Host", host)
              .header("Tailscale-User-Login", "laura@x.com").header("Tailscale-User-Name", "Laura")
              .with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }))
          .andExpect(status().isOk()).andExpect(jsonPath("$.login").value("laura@x.com"))
          .andExpect(jsonPath("$.name").value("Laura"));
    }
  }

  @Test
  void tailscaleHttpsMutationRequiresMatchingOrigin() throws Exception {
    mvc.perform(post("/api/v1/customers").header("Host", TAILSCALE_HOST)
            .header("Origin", "https://" + TAILSCALE_HOST).header("X-Requested-With", "papeleria-ui")
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TS1\",\"name\":\"Tailscale\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/customers").header("Host", TAILSCALE_HOST)
            .header("Origin", "https://otro.tail123.ts.net").header("X-Requested-With", "papeleria-ui")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ORIGIN"));
  }

  @Test
  void tailscaleIdentityCannotBeSpoofedFromTailnetIp() throws Exception {
    mvc.perform(get("/api/v1/me").header("Host", TAILSCALE_HOST)
            .header("Tailscale-User-Login", "laura@x.com")
            .with(r -> { r.setRemoteAddr("100.71.114.124"); return r; }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.login").value(org.hamcrest.Matchers.startsWith("local@")));
  }

  @Test
  @Order(1)
  void pinFlow() throws Exception {
    var h = new org.springframework.http.HttpHeaders();
    h.add("X-Requested-With", "papeleria-ui");

    long id = createCustomer();
    // sin PIN configurado
    mvc.perform(post("/api/v1/customers/" + id + "/adjustments").headers(h)
            .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Pin", "1234")
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":100,\"note\":\"x\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_NOT_SET"));

    mvc.perform(post("/api/v1/security/pin").headers(h).contentType(MediaType.APPLICATION_JSON)
            .content("{\"pin\":\"1234\"}")).andExpect(status().isOk());

    // 5 fallos -> bloqueo
    for (int i = 0; i < 4; i++) {
      mvc.perform(adjust(id, "0000")).andExpect(jsonPath("$.code").value("PIN_INVALID"));
    }
    mvc.perform(adjust(id, "0000")).andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("PIN_LOCKED"));
    // incluso el PIN correcto queda bloqueado
    mvc.perform(adjust(id, "1234")).andExpect(status().isLocked());
  }

  @Test
  void duplicateCodeIs409AndBadParamIs400() throws Exception {
    String body = "{\"code\":\"DUP1\",\"name\":\"Uno\"}";
    mvc.perform(post("/api/v1/customers").header("X-Requested-With", "papeleria-ui")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    mvc.perform(post("/api/v1/customers").header("X-Requested-With", "papeleria-ui")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"));
    mvc.perform(get("/api/v1/ledger?type=XXX")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private org.springframework.test.web.servlet.RequestBuilder adjust(long id, String pin) {
    return post("/api/v1/customers/" + id + "/adjustments").header("X-Requested-With", "papeleria-ui")
        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Pin", pin)
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":100,\"note\":\"x\"}");
  }

  private long createCustomer() throws Exception {
    var body = mvc.perform(post("/api/v1/customers").header("X-Requested-With", "papeleria-ui")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"W1\",\"name\":\"Web Test\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
  }
}
