package com.petwal.comparator;

import java.util.Comparator;

/**
 * Utility class providing {@link java.util.Comparator} instances for {@code byte[]} keys.
 *
 * <p>Bytes are treated as unsigned values (0–255) so that the natural lexicographic order matches
 * the expected sort order for arbitrary binary keys.
 */
public final class ByteArrayComparators {

  private ByteArrayComparators() {
  }

  public static final Comparator<byte[]> LEXICOGRAPHIC =
      (a, b) -> {
        final int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
          final int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
          if (diff != 0) return diff;
        }
        return a.length - b.length;
      };

}
