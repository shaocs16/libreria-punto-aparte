package com.papeleria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class FeaturesIntegrationTest {
  static final Path DB;
  static {
    try {
      DB = Files.createTempDirectory("papeleria-feat").resolve("feat.db");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.db-path", () -> DB.toString());
    r.add("app.allowed-hosts", () -> "localhost");
  }

  static final String PIN = "4321";
  static final ObjectMapper OM = new ObjectMapper();
  static final Charset WIN = Charset.forName("windows-1252");

  @Autowired WebApplicationContext ctx;
  MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(ctx)
        .defaultRequest(get("/").header("Host", "localhost"))
        .addFilters(ctx.getBean(com.papeleria.common.LocalOnlyFilter.class),
            ctx.getBean(com.papeleria.common.ActorFilter.class))
        .build();
    var me = OM.readTree(mvc.perform(get("/api/v1/me")).andReturn().getResponse().getContentAsString());
    if (!me.get("pinSet").asBoolean()) {
      mvc.perform(mut(post("/api/v1/security/pin")).contentType(MediaType.APPLICATION_JSON)
          .content("{\"pin\":\"" + PIN + "\"}")).andExpect(status().isOk());
    }
  }

  static MockHttpServletRequestBuilder mut(MockHttpServletRequestBuilder b) {
    return b.header("X-Requested-With", "papeleria-ui");
  }

  JsonNode json(MvcResult r) throws Exception {
    return OM.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  long createCustomer(String code, String name) throws Exception {
    return json(mvc.perform(mut(post("/api/v1/customers")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn()).get("id").asLong();
  }

  MvcResult upload(String kind, String filename, byte[] bytes) throws Exception {
    return mvc.perform(mut(multipart("/api/v1/imports")
        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
        .param("kind", kind))).andReturn();
  }

  JsonNode customerByCode(String code) throws Exception {
    var page = json(mvc.perform(get("/api/v1/customers").param("q", code)).andReturn());
    for (var c : page.get("items")) if (c.get("code").asText().equals(code)) return c;
    return null;
  }

  @Test
  void createWithoutCodeAssignsOneViaApi() throws Exception {
    var created = json(mvc.perform(mut(post("/api/v1/customers")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Sin código API\"}")).andExpect(status().isCreated()).andReturn());
    assertThat(created.get("code").asText()).matches("C\\d{3,}");
    // sin nombre sigue siendo un error de validación
    mvc.perform(mut(post("/api/v1/customers")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void balanceIsNeverServedFromHttpCache() throws Exception {
    long id = createCustomer("CACHE1", "Cache Uno");
    var first = mvc.perform(get("/api/v1/customers/" + id)).andExpect(status().isOk()).andReturn();
    assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(first.getResponse().getHeader("ETag")).isNull();

    mvc.perform(mut(post("/api/v1/customers/" + id + "/topups")).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":2000}")).andExpect(status().isCreated());

    // un navegador que revalida con la versión antigua debe recibir 200 con el saldo nuevo, nunca 304
    mvc.perform(get("/api/v1/customers/" + id).header("If-None-Match", "\"0\""))
        .andExpect(status().isOk()).andExpect(jsonPath("$.balanceCents").value(2000));
  }

  @Test
  void searchUsesFtsAndPrefix() throws Exception {
    createCustomer("S1", "María Gómez");
    createCustomer("S2", "Pedro Ruiz");
    assertThat(json(mvc.perform(get("/api/v1/customers").param("q", "gom")).andReturn()).get("total").asInt())
        .isEqualTo(1);
    assertThat(json(mvc.perform(get("/api/v1/customers").param("q", "MARÍA")).andReturn()).get("total").asInt())
        .isEqualTo(1);
    assertThat(json(mvc.perform(get("/api/v1/customers").param("q", "ru")).andReturn()).get("total").asInt())
        .isEqualTo(1);
    assertThat(json(mvc.perform(get("/api/v1/customers").param("q", "zzzz")).andReturn()).get("total").asInt())
        .isZero();
    assertThat(json(mvc.perform(get("/api/v1/customers/suggest").param("q", "pedro")).andReturn()).size())
        .isEqualTo(1);
    // comillas en la búsqueda no rompen la consulta FTS
    mvc.perform(get("/api/v1/customers").param("q", "a\"b")).andExpect(status().isOk());
  }

  @Test
  void importCustomersWindows1252ThenRejectSameFile() throws Exception {
    String csv = "code;name;phone;document;notes;initial_balance\r\n"
        + "I1;José Núñez;600123456;X1234567;nota;12,50\r\n"
        + "I2;Ana;;;;\r\n";
    var created = upload("CUSTOMERS", "clientes.csv", csv.getBytes(WIN));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    var job = json(created);
    assertThat(job.get("errorRows").asInt()).isZero();
    assertThat(job.get("okRows").asInt()).isEqualTo(2);
    assertThat(job.get("sample").get(0).get("values").get("name").asText()).isEqualTo("José Núñez");

    mvc.perform(mut(post("/api/v1/imports/" + job.get("id").asText() + "/confirm")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));
    var c = customerByCode("I1");
    assertThat(c.get("name").asText()).isEqualTo("José Núñez");
    assertThat(c.get("balanceCents").asLong()).isEqualTo(1250);
    assertThat(customerByCode("I2").get("balanceCents").asLong()).isZero();

    var again = upload("CUSTOMERS", "clientes.csv", csv.getBytes(WIN));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(json(again).get("code").asText()).isEqualTo("FILE_ALREADY_IMPORTED");
  }

  @Test
  void importWithErrorsCannotBeConfirmed() throws Exception {
    String csv = "code,name,initial_balance\nE1,Uno,1.234\nE1,Dos,5\n,Sin código,\n";
    var res = upload("CUSTOMERS", "malo.csv", csv.getBytes(StandardCharsets.UTF_8));
    var job = json(res);
    assertThat(job.get("errorRows").asInt()).isEqualTo(3);

    mvc.perform(mut(post("/api/v1/imports/" + job.get("id").asText() + "/confirm")))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));

    var csvErr = mvc.perform(get("/api/v1/imports/" + job.get("id").asText() + "/errors.csv"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(csvErr).startsWith("\uFEFFfila;columna;mensaje").contains("initial_balance").contains("duplicado");
    assertThat(customerByCode("E1")).isNull();
  }

  @Test
  void importMovementsNeedsPinAndChecksAccumulatedBalance() throws Exception {
    createCustomer("MV1", "Mov Uno");
    String bad = "row_id,customer_code,type,amount,note\nbad-1,MV1,TOPUP,5.00,x\nbad-2,MV1,SPEND,9.00,y\n";
    var badJob = json(upload("MOVEMENTS", "mov-bad.csv", bad.getBytes(StandardCharsets.UTF_8)));
    assertThat(badJob.get("errorRows").asInt()).isEqualTo(1);
    assertThat(badJob.get("errors").get(0).get("errors").get(0).get("message").asText()).contains("saldo insuficiente");

    String ok = "row_id,customer_code,type,amount,note\nok-1,MV1,TOPUP,10.00,recarga\nok-2,MV1,SPEND,2.50,compra\n";
    var job = json(upload("MOVEMENTS", "mov-ok.csv", ok.getBytes(StandardCharsets.UTF_8)));
    String id = job.get("id").asText();
    mvc.perform(mut(post("/api/v1/imports/" + id + "/confirm")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
    mvc.perform(mut(post("/api/v1/imports/" + id + "/confirm")).header("X-Pin", PIN)).andExpect(status().isOk());
    assertThat(customerByCode("MV1").get("balanceCents").asLong()).isEqualTo(750);

    // misma row_id en otro fichero distinto: se detecta como ya importada
    String dup = "row_id,customer_code,type,amount,note\nok-1,MV1,TOPUP,10.00,otra vez\n";
    var dupJob = json(upload("MOVEMENTS", "mov-dup.csv", dup.getBytes(StandardCharsets.UTF_8)));
    assertThat(dupJob.get("errorRows").asInt()).isEqualTo(1);
  }

  @Test
  void importXlsx() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var wb = new XSSFWorkbook()) {
      var sh = wb.createSheet();
      var h = sh.createRow(0);
      h.createCell(0).setCellValue("Código");
      h.createCell(1).setCellValue("Nombre");
      h.createCell(2).setCellValue("Saldo inicial");
      var r = sh.createRow(1);
      r.createCell(0).setCellValue("X1");
      r.createCell(1).setCellValue("Laura Díaz");
      r.createCell(2).setCellValue(7.5);
      wb.write(out);
    }
    var job = json(upload("CUSTOMERS", "clientes.xlsx", out.toByteArray()));
    assertThat(job.get("errorRows").asInt()).isZero();
    mvc.perform(mut(post("/api/v1/imports/" + job.get("id").asText() + "/confirm"))).andExpect(status().isOk());
    assertThat(customerByCode("X1").get("balanceCents").asLong()).isEqualTo(750);
  }

  @Test
  void rejectsFakeXlsxAndDiscard() throws Exception {
    assertThat(upload("CUSTOMERS", "falso.xlsx", "code;name\nA;B\n".getBytes()).getResponse().getStatus())
        .isEqualTo(400);
    var job = json(upload("CUSTOMERS", "descartar.csv", "code;name\nD1;Desc\n".getBytes()));
    mvc.perform(mut(delete("/api/v1/imports/" + job.get("id").asText()))).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/imports/" + job.get("id").asText())).andExpect(jsonPath("$.status").value("DISCARDED"));
    assertThat(customerByCode("D1")).isNull();
  }

  @Test
  void templatesAndExports() throws Exception {
    var tpl = mvc.perform(get("/api/v1/imports/templates/customers.csv")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(tpl).startsWith("\uFEFFcode;name;phone;document;notes;initial_balance");

    long id = createCustomer("EX1", "=cmd Export");
    mvc.perform(mut(post("/api/v1/customers/" + id + "/topups")).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":1250}")).andExpect(status().isCreated());

    var csv = mvc.perform(get("/api/v1/exports/customers.csv")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("\uFEFFCódigo;Nombre").contains("EX1;'=cmd Export").contains("12,50");

    var ledger = mvc.perform(get("/api/v1/exports/ledger.csv").param("customerId", String.valueOf(id))
        .param("from", "2020-01-01").param("to", "2100-01-01")).andReturn().getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(ledger).contains("TOPUP").contains("12,50");

    var xlsx = mvc.perform(get("/api/v1/exports/ledger.xlsx")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(xlsx[0]).isEqualTo((byte) 'P');
    mvc.perform(get("/api/v1/exports/audit.csv")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/exports/nada.csv")).andExpect(status().isNotFound());
  }

  @Test
  void dashboardReceiptAndAudit() throws Exception {
    long id = createCustomer("DB1", "Dash Uno");
    var key = UUID.randomUUID().toString();
    var topup = json(mvc.perform(mut(post("/api/v1/customers/" + id + "/topups")).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":2000}")).andReturn());
    mvc.perform(mut(post("/api/v1/customers/" + id + "/spends")).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":500}")).andExpect(status().isCreated());

    var d = json(mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isOk()).andReturn());
    assertThat(d.get("totalBalanceCents").asLong()).isGreaterThanOrEqualTo(1500);
    assertThat(d.get("series").size()).isGreaterThanOrEqualTo(1);
    assertThat(d.get("totals").get("spendsCents").asLong()).isGreaterThanOrEqualTo(500);
    assertThat(d.get("averageTicketCents").asLong()).isGreaterThan(0);
    assertThat(d.get("topCustomersByBalance").size()).isGreaterThanOrEqualTo(1);

    mvc.perform(get("/api/v1/ledger/" + topup.get("id").asLong() + "/receipt")).andExpect(status().isOk())
        .andExpect(jsonPath("$.customer.code").value("DB1")).andExpect(jsonPath("$.businessName").exists());

    mvc.perform(get("/api/v1/audit").param("entity", "customer").param("id", String.valueOf(id)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].action").value("CUSTOMER_CREATED"));
    mvc.perform(get("/api/v1/dashboard/summary").param("from", "no-fecha")).andExpect(status().isBadRequest());
  }

  @Test
  void settingsRequirePinAndValidate() throws Exception {
    mvc.perform(mut(put("/api/v1/settings")).contentType(MediaType.APPLICATION_JSON).content("{\"low_balance_cents\":\"900\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
    mvc.perform(mut(put("/api/v1/settings")).header("X-Pin", PIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"low_balance_cents\":\"abc\"}")).andExpect(status().isBadRequest());
    mvc.perform(mut(put("/api/v1/settings")).header("X-Pin", PIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"pin_hash\":\"x\"}")).andExpect(status().isBadRequest());
    mvc.perform(mut(put("/api/v1/settings")).header("X-Pin", PIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"backup_enabled\":\"true\",\"backup_dir\":\"\"}")).andExpect(status().isBadRequest());
    mvc.perform(mut(put("/api/v1/settings")).header("X-Pin", PIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"low_balance_cents\":\"900\",\"business_name\":\"Mi Papelería\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.low_balance_cents").value("900"));
    var s = json(mvc.perform(get("/api/v1/settings")).andReturn());
    assertThat(s.has("pin_hash")).isFalse();
  }

  @Test
  void backupDownloadAndConfiguredBackupWithRotation() throws Exception {
    mvc.perform(mut(post("/api/v1/backup/download"))).andExpect(status().isForbidden());
    var bytes = mvc.perform(mut(post("/api/v1/backup/download")).header("X-Pin", PIN)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(new String(bytes, 0, 15, StandardCharsets.US_ASCII)).isEqualTo("SQLite format 3");

    Path dir = Files.createTempDirectory("papeleria-bk");
    mvc.perform(mut(put("/api/v1/settings")).header("X-Pin", PIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"backup_enabled\":\"true\",\"backup_dir\":\"" + dir.toString().replace("\\", "\\\\") + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(mut(post("/api/v1/backup/run")).header("X-Pin", PIN)).andExpect(status().isOk());
    try (var files = Files.list(dir)) {
      assertThat(files.filter(p -> p.toString().endsWith(".db")).count()).isEqualTo(1);
    }
    mvc.perform(get("/api/v1/health")).andExpect(jsonPath("$.backup.configured").value(true));
  }
}
