package com.papeleria.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class Money {
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

  private Money() {}

  /** Acepta "12,50" y "12.50"; más de 2 decimales o formato ambiguo es error. */
  public static long parse(String s) {
    if (s == null || s.isBlank()) throw new IllegalArgumentException("Importe vacío");
    String t = s.strip().replace("€", "").replace(" ", "").replace("\u00a0", "");
    if (t.contains(",") && t.contains(".")) throw new IllegalArgumentException("Formato de importe ambiguo");
    t = t.replace(',', '.');
    if (!NUMBER.matcher(t).matches()) throw new IllegalArgumentException("Importe no válido");
    try {
      return new BigDecimal(t).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("El importe admite como máximo 2 decimales");
    }
  }

  public static BigDecimal toDecimal(long cents) { return BigDecimal.valueOf(cents, 2); }

  public static String format(long cents) {
    long abs = Math.abs(cents);
    return (cents < 0 ? "-" : "") + abs / 100 + "," + String.format("%02d", abs % 100);
  }
}
