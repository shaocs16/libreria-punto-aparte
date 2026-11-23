import { centsToInput, formatCents, parseEuros } from "../../src/lib/money";

describe("parseEuros", () => {
  it.each([
    ["12,50", 1250],
    ["12.50", 1250],
    ["12", 1200],
    ["0,05", 5],
    [",5", 50],
    ["1.250,50", 125050],
    ["1,250.50", 125050],
    ["1.250", 125000],
    ["12,", 1200],
    ["12,5 €", 1250],
    ["-3,20", -320],
  ])("%s -> %d céntimos", (input, cents) => {
    expect(parseEuros(input)).toBe(cents);
  });

  it.each(["", "abc", "12,505", "1,2,3", "1,234", "--5", "1e3"])(
    "rechaza %j",
    (input) => {
      expect(parseEuros(input)).toBeNull();
    },
  );

  it("no pierde precisión con importes que fallan en coma flotante", () => {
    expect(parseEuros("0,29")).toBe(29); // 0.29 * 100 = 28.999999999999996 en float
    expect(parseEuros("1.005,07")).toBe(100507);
  });
});

describe("formato", () => {
  it("formatCents usa es-ES con euros", () => {
    expect(formatCents(123456).replace(/\s/g, " ")).toBe("1234,56 €");
    expect(formatCents(5)).toContain("0,05");
  });
  it("centsToInput es reversible con parseEuros", () => {
    for (const c of [0, 5, 100, 1250, 99999, -320])
      expect(parseEuros(centsToInput(c))).toBe(c);
  });
});
