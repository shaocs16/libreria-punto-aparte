# Papelería: sistema visual y prácticas de frontend

Interfaz de trabajo para un mostrador: rapidez, legibilidad de importes y cero ambigüedad al tocar dinero. No es una landing ni un escaparate.

## Qué se ha tomado de `documentos/rust` (Rust Atlas) y qué no

Se analizó ese directorio (curso web con Vite y JS puro, `DESIGN.md`, `VERIFICACION.md`, Playwright). El código Rust no es relevante aquí; sí lo eran sus prácticas de interfaz.

**Aplicado**
- Accesibilidad: skip link, foco visible (`:focus-visible`), región `role="status"` para avisos, `aria-pressed`/`aria-checked`, `aria-current`, `<progress>` nativo donde procede, menú móvil con `inert`, cierre con Escape y foco devuelto al botón, foco al contenido (`main tabindex=-1`) tras cada navegación.
- Rutas con hash (`HashRouter`): enlaces profundos sin configurar fallback de rutas en Spring; título del documento por vista.
- Atajo `Ctrl+K` para buscar.
- Fuentes locales (`@fontsource`): la CSP del backend es `default-src 'self'`, no se pueden cargar fuentes externas.
- Tokens CSS, superficies planas (sin gradientes, blur ni sombras estáticas), números con `tabular-nums`.
- `prefers-reduced-motion`, objetivos táctiles de 44 px, tablas con scroll interno (nunca desbordan la página), `noscript`.
- `localStorage` siempre con `try/catch` (`safeStorage`).
- Pruebas Playwright a 1440, 768 y 390 px: errores de consola, desbordamiento horizontal, enlaces profundos, teclado.
- `VERIFICACION.md` con resultados ejecutados, sin afirmar lo que no se ha comprobado.

**Descartado**
- Plantillas `innerHTML` de JS puro: con nombres de clientes dinámicos es riesgo de XSS. React escapa por defecto y no se usa `dangerouslySetInnerHTML`.
- Plugin Vite de markdown, `marked`, `highlight.js`, lecciones, terminal, hero y tipografía serif editorial: contenido de un curso.
- Progreso en `localStorage`: aquí el estado vive en el servidor.
- `vite --host 0.0.0.0`: la app solo escucha en loopback (`127.0.0.1`).
- Playwright MCP, skills Taste y `.mcp.json`: herramientas de desarrollo ajenas al producto.

## Tokens

Fondo blanco, tinta `#1f201d`, superficie `#f6f7f6`, secundario `#5b5c59`, acento verde azulado `#1b6e66`, peligro `#a3261c`. El acento indica acción o dato positivo. Radio de panel 16 px y de control 10 px (menor que en Rust Atlas: hay tablas densas). Tipografía Instrument Sans; monoespaciada solo en `code`.

## Decisiones propias de una caja

- El importe se escribe como texto (`12,50`) y el estado guarda **céntimos enteros**; nunca `float`. La coma como único separador es siempre decimal: `1,234` es inválido (evita cobrar 1.234 € por un error de tecleo). `1.250` se lee como mil doscientos cincuenta.
- Botón deshabilitado mientras se envía y `Idempotency-Key` estable entre reintentos, renovada tras éxito.
- PIN en modal nativo `<dialog>`; solo vive en el estado del diálogo mientras está abierto.
- Avisos permanentes (sin backup, saldos que no cuadran, sin conexión) arriba, con `role="alert"` o `note`.
- El botón de operación indica el importe ("Cobrar 2,50 €") y 🔒 si pide PIN.
- Ticket imprimible: `@media print` oculta toda la interfaz y deja 72 mm.

## Desviaciones registradas

- La hoja de Rust Atlas pide ritmo editorial; aquí se prioriza densidad de datos y formularios.
- Las cifras del panel son KPI reales calculados por el backend; no hay métricas ficticias.
