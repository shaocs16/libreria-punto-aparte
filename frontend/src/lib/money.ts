const eur = new Intl.NumberFormat("es-ES", {
  style: "currency",
  currency: "EUR",
});

export function formatCents(cents: number): string {
  return eur.format(cents / 100);
}

/**
 * Convierte texto del usuario a céntimos. Acepta "12,50", "12.50", "1.250,50", "1,250.50".
 * Devuelve null si no es un importe válido o tiene más de 2 decimales. Nunca usa floats.
 */
export function parseEuros(input: string): number | null {
  let t = input.replace(/[€\s\u00a0]/g, "");
  if (!t) return null;
  const negative = t.startsWith("-");
  if (negative) t = t.slice(1);
  const lastComma = t.lastIndexOf(",");
  const lastDot = t.lastIndexOf(".");
  const decimalSep =
    lastComma > lastDot ? "," : lastDot > lastComma ? "." : null;
  let int = t;
  let dec = "";
  if (decimalSep) {
    const idx = t.lastIndexOf(decimalSep);
    int = t.slice(0, idx);
    dec = t.slice(idx + 1);
    const thousands = decimalSep === "," ? "." : ",";
    // Con la coma como único separador (convención es-ES) siempre es decimal: "1,234" no es válido.
    // Con un punto y exactamente 3 dígitos detrás ("1.250") solo cabe leerlo como miles.
    if (
      decimalSep === "." &&
      dec.length === 3 &&
      !int.includes(thousands) &&
      !int.includes(decimalSep) &&
      int !== ""
    ) {
      int = int + dec;
      dec = "";
    } else {
      int = int.split(thousands).join("");
    }
  }
  if (!/^\d+$/.test(int === "" ? "0" : int) || (dec && !/^\d{1,2}$/.test(dec)))
    return null;
  const cents =
    Number(int === "" ? "0" : int) * 100 + Number((dec + "00").slice(0, 2));
  if (!Number.isSafeInteger(cents)) return null;
  return negative ? -cents : cents;
}

/** Céntimos a texto editable ("12,50"). */
export function centsToInput(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? "-" : ""}${Math.floor(abs / 100)},${String(abs % 100).padStart(2, "0")}`;
}
