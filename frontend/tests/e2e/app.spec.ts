import { expect, test, type Page } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";

const PIN = "4321";

function trackErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  return errors;
}

async function ensurePin(page: Page) {
  await page.goto("/#/ajustes");
  const create = page.getByRole("heading", {
    name: "Primer arranque: crea el PIN",
  });
  await expect(
    page.getByRole("heading", { level: 1, name: "Ajustes" }),
  ).toBeVisible();
  if (await create.isVisible().catch(() => false)) {
    await page.getByLabel("PIN", { exact: true }).fill(PIN);
    await page.getByLabel("Repite el PIN").fill(PIN);
    await page.getByRole("button", { name: "Crear PIN" }).click();
    await expect(page.getByRole("status")).toContainText("PIN creado");
  }
}

async function createCustomer(page: Page, name: string) {
  await page.goto("/#/clientes");
  await page.getByRole("button", { name: "Nuevo cliente" }).click();
  // el código lo asigna el servidor: el formulario de alta no lo pide
  await expect(page.getByLabel("Código")).toHaveCount(0);
  await page.getByLabel("Nombre", { exact: true }).fill(name);
  await page.getByRole("button", { name: "Crear cliente" }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(name);
  await expect(page.getByText(/^C\d{3,}$/)).toBeVisible(); // código automático
}

async function enterPin(page: Page, pin = PIN) {
  const input = page.getByLabel("PIN", { exact: true });
  await expect(input).toBeVisible();
  await input.fill(pin);
  await page.getByRole("button", { name: "Confirmar" }).click();
}

test("primer arranque, caja, recarga, consumo, anulación con PIN y ticket", async ({
  page,
}, testInfo) => {
  const errors = trackErrors(page);
  await ensurePin(page);

  const stamp = `${testInfo.project.name}-${Date.now()}`;
  await createCustomer(page, `Cliente ${stamp}`);
  const detailUrl = page.url();

  // Caja: buscar por código, recargar 20 y cobrar 2,50
  await page.goto("/#/");
  await expect(page.getByRole("combobox")).toBeFocused();
  await page.getByRole("combobox").fill(stamp);
  await page
    .getByRole("option", { name: new RegExp(`Cliente ${stamp}`) })
    .click();
  await expect(page.getByTestId("balance")).toContainText("0,00");

  await page.getByRole("radio", { name: "Recarga" }).click();
  await page.getByLabel("Importe", { exact: true }).fill("20");
  await page.getByRole("button", { name: /^Recargar/ }).click();
  await expect(page.getByTestId("balance")).toContainText("20,00");

  await page.getByRole("radio", { name: "Consumo" }).click();
  await page.getByLabel("Importe", { exact: true }).fill("2,50");
  await page.getByRole("button", { name: /^Cobrar/ }).dblclick(); // doble clic: solo un cobro
  await expect(page.getByTestId("balance")).toContainText("17,50");
  await page.screenshot({
    path: testInfo.outputPath("caja.png"),
    fullPage: true,
  });

  // Saldo insuficiente
  await page.getByLabel("Importe", { exact: true }).fill("100");
  await page.getByRole("button", { name: /^Cobrar/ }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Saldo insuficiente" }),
  ).toBeVisible();
  await expect(page.getByTestId("balance")).toContainText("17,50");

  // Detalle: anular el consumo con PIN (primero PIN incorrecto)
  await page.goto(detailUrl);
  await expect(page.getByTestId("balance")).toContainText("17,50");
  const spendRow = page.getByRole("row").filter({ hasText: "Consumo" });
  await spendRow.getByRole("button", { name: "Anular" }).click();
  await page.getByLabel("Motivo (obligatorio)").fill("cobrado por error");
  await page
    .getByRole("button", { name: /^Anular/ })
    .last()
    .click();
  await enterPin(page, "0000");
  await expect(
    page.getByRole("alert").filter({ hasText: "PIN incorrecto" }),
  ).toBeVisible();
  await page.getByLabel("PIN", { exact: true }).fill(PIN);
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page.getByTestId("balance")).toContainText("20,00");
  await expect(
    page.getByRole("row").filter({ hasText: "Anulado" }),
  ).toHaveCount(1);

  // Ticket imprimible
  await page.getByRole("link", { name: "Ticket" }).first().click();
  await expect(page.getByRole("article", { name: "Ticket" })).toContainText(
    "Saldo tras la operación",
  );

  // el test provoca a propósito un 409 (saldo insuficiente) y un 403 (PIN incorrecto): Chrome los anota como errores de red
  const unexpected = errors.filter(
    (e) =>
      !/Failed to load resource: the server responded with a status of (403|409)/.test(
        e,
      ),
  );
  expect(unexpected).toEqual([]);
  expect(errors.filter((e) => /409/.test(e))).toHaveLength(1);
});

test("auditoría, movimientos, panel y exportaciones", async ({ page }) => {
  const errors = trackErrors(page);
  await ensurePin(page);

  await page.goto("/#/auditoria");
  await expect(
    page.getByRole("row").filter({ hasText: "PIN_CREATED" }).first(),
  ).toBeVisible();

  await page.goto("/#/movimientos");
  await expect(page.getByText(/movimientos · Entradas/)).toBeVisible();

  await page.goto("/#/panel");
  await expect(page.getByText("Saldo total en circulación")).toBeVisible();

  const csv = await page.request.get("/api/v1/exports/customers.csv");
  expect(csv.ok()).toBeTruthy();
  expect((await csv.text()).startsWith("\uFEFFCódigo;Nombre")).toBeTruthy();
  expect(errors).toEqual([]);
});

test("importación de clientes: vista previa con errores y confirmación", async ({
  page,
}, testInfo) => {
  const errors = trackErrors(page);
  await ensurePin(page);
  const tag = `${testInfo.project.name.slice(0, 2)}${Date.now() % 1_000_000}`;
  mkdirSync(testInfo.outputDir, { recursive: true });

  const bad = `${testInfo.outputDir}/malo.csv`;
  writeFileSync(
    bad,
    `code;name;initial_balance\n${tag}A;Uno;1,234\n${tag}A;Dos;5\n`,
  );
  await page.goto("/#/importar");
  await page.getByLabel(/Fichero/).setInputFiles(bad);
  await page.getByRole("button", { name: "Subir y validar" }).click();
  await expect(page.getByText("2 con errores")).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Confirmar e importar/ }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Descartar" }).click();

  const good = `${testInfo.outputDir}/bueno.csv`;
  writeFileSync(
    good,
    `code;name;initial_balance\n${tag}A;María Núñez;12,50\n${tag}B;Pedro;\n`,
  );
  await page.getByLabel(/Fichero/).setInputFiles(good);
  await page.getByRole("button", { name: "Subir y validar" }).click();
  await expect(page.getByText("2 correctas")).toBeVisible();
  await page.getByRole("button", { name: /Confirmar e importar/ }).click();
  await expect(page.getByText(/Estado: EXECUTED/)).toBeVisible();

  await page.goto(`/#/clientes?q=${tag}A`);
  await expect(page.getByRole("link", { name: "María Núñez" })).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: tag + "A" }),
  ).toContainText("12,50");
  expect(errors).toEqual([]);
});

test("ajustes protegidos con PIN y copia descargable", async ({
  page,
}, testInfo) => {
  const errors = trackErrors(page);
  await ensurePin(page);
  await page.goto("/#/ajustes");
  await expect(
    page.getByText("Sin copia de seguridad configurada").first(),
  ).toBeVisible();

  await page
    .getByLabel("Pie del ticket")
    .fill(`Vuelva pronto (${testInfo.project.name})`);
  await page.getByRole("button", { name: /Guardar ajustes/ }).click();
  await enterPin(page);
  await expect(
    page.getByRole("status").filter({ hasText: "Ajustes guardados" }),
  ).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: /Descargar copia ahora/ }).click();
  await enterPin(page);
  const dl = await downloadPromise;
  expect(dl.suggestedFilename()).toMatch(/^papeleria-\d{8}-\d{4}\.db$/);
  expect(errors).toEqual([]);
});

test("todas las vistas sin desbordamiento horizontal y enlaces profundos", async ({
  page,
}, testInfo) => {
  const errors = trackErrors(page);
  await ensurePin(page);
  for (const route of [
    "/",
    "/clientes",
    "/movimientos",
    "/panel",
    "/importar",
    "/auditoria",
    "/ajustes",
    "/clientes/999999",
    "/no-existe",
  ]) {
    await page.goto(`/#${route}`);
    await expect(page.locator("h1").first()).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const fits = await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    );
    expect(
      fits,
      `desbordamiento en ${route} (${testInfo.project.name})`,
    ).toBeTruthy();
  }
  await page.goto("/#/clientes?q=zzzz-no-existe");
  await expect(page.getByText("No hay clientes que coincidan")).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("clientes.png"),
    fullPage: true,
  });
  expect(errors.filter((e) => !/404|Failed to load resource/.test(e))).toEqual(
    [],
  );
});

test("teclado: Ctrl+K, saltar al contenido y menú móvil con Escape", async ({
  page,
}, testInfo) => {
  await ensurePin(page);
  await page.goto("/#/clientes");
  await page.keyboard.press("Control+k");
  await expect(page.getByLabel("Buscar clientes")).toBeFocused();

  await page.keyboard.press("Tab");
  if (testInfo.project.name === "mobile") {
    const open = page.getByRole("button", { name: "Menú" });
    await open.click();
    await expect(
      page.getByRole("navigation", { name: "Principal" }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(open).toBeFocused();
    await expect(
      page.getByRole("navigation", { name: "Principal" }),
    ).not.toBeVisible();
  } else {
    await expect(
      page.getByRole("navigation", { name: "Principal" }),
    ).toBeVisible();
    await page.getByRole("link", { name: "Movimientos" }).click();
    await expect(page).toHaveURL(/#\/movimientos$/);
    await expect(page.locator("#main")).toBeFocused();
  }
});

test("editar cliente: el código es de solo lectura y se conserva", async ({
  page,
}, testInfo) => {
  const errors = trackErrors(page);
  await ensurePin(page);
  const name = `Editable ${testInfo.project.name}-${Date.now()}`;
  await createCustomer(page, name);
  const code = (await page.getByText(/^C\d{3,}$/).innerText()).trim();

  await page.getByRole("button", { name: "Editar" }).click();
  const codeInput = page.getByLabel(/^Código/);
  await expect(codeInput).toHaveValue(code);
  await expect(codeInput).toHaveAttribute("readonly", "");
  await expect(codeInput).toHaveAttribute("aria-readonly", "true");

  await page.getByLabel("Nombre", { exact: true }).fill(`${name} (editado)`);
  await page.getByRole("button", { name: "Guardar cambios" }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "(editado)",
  );
  await expect(page.getByText(code, { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
