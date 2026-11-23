// Arranca el jar real (con el frontend ya compilado dentro) sobre una BD limpia para el E2E.
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const jar = resolve(here, "../../../backend/target/papeleria-1.0.0.jar");
if (!existsSync(jar)) {
  console.error(`No existe ${jar}. Ejecuta: npm run build && (cd ../backend && mvn package -DskipTests)`);
  process.exit(1);
}
const dir = resolve(process.env.E2E_DIR ?? "C:/Temp/opencode/e2e");
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });

const child = spawn("java", ["-jar", jar, `--app.db-path=${dir}/e2e.db`, "--server.port=8080"], { stdio: "inherit" });
child.on("exit", (code) => process.exit(code ?? 0));
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => child.kill());
