package com.papeleria.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class Ids {
  private static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

  private Ids() {}

  /** UUID v5 (basado en nombre, SHA-1). */
  public static UUID v5(String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits())
          .putLong(NAMESPACE.getLeastSignificantBits()).array());
      byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
      h[6] = (byte) ((h[6] & 0x0f) | 0x50);
      h[8] = (byte) ((h[8] & 0x3f) | 0x80);
      ByteBuffer bb = ByteBuffer.wrap(h);
      return new UUID(bb.getLong(), bb.getLong());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
