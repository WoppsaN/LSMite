package com.petwal.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ByteArrayComparators#LEXICOGRAPHIC}.
 *
 * <p>Focuses on unsigned byte ordering and prefix semantics, both of which differ from the
 * behaviour of a naïve signed-byte or {@code Arrays.compare} approach.
 */
final class ByteArrayComparatorsTest {

  @Test
  void compare_identicalArrays_returnsZero() {
    final int result =
        ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {1, 2, 3}, new byte[] {1, 2, 3});
    assertEquals(0, result);
  }

  @Test
  void compare_firstLessThan_returnsNegative() {
    final int result = ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {1}, new byte[] {2});
    assertTrue(result < 0);
  }

  @Test
  void compare_firstGreaterThan_returnsPositive() {
    final int result = ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {2}, new byte[] {1});
    assertTrue(result > 0);
  }

  @Test
  void compare_shorterPrefix_returnsNegative() {
    final int result =
        ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {1, 2}, new byte[] {1, 2, 3});
    assertTrue(result < 0);
  }

  @Test
  void compare_longerPrefix_returnsPositive() {
    final int result =
        ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {1, 2, 3}, new byte[] {1, 2});
    assertTrue(result > 0);
  }

  @Test
  void compare_bothEmpty_returnsZero() {
    final int result = ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {}, new byte[] {});
    assertEquals(0, result);
  }

  @Test
  void compare_emptyVsNonEmpty_returnsNegative() {
    final int result = ByteArrayComparators.LEXICOGRAPHIC.compare(new byte[] {}, new byte[] {0});
    assertTrue(result < 0);
  }

  @Test
  void compare_unsignedOrdering_0xFF_greaterThan_0x01() {
    // If bytes were treated as signed, 0xFF = -1 would sort before 0x01 = 1.
    // Unsigned treatment must give the opposite result.
    final int result =
        ByteArrayComparators.LEXICOGRAPHIC.compare(
            new byte[] {(byte) 0xFF}, new byte[] {(byte) 0x01});
    assertTrue(result > 0);
  }

  @Test
  void compare_unsignedOrdering_0x80_greaterThan_0x7F() {
    // 0x80 = -128 signed; 0x7F = 127 signed.  Unsigned: 128 > 127.
    final int result =
        ByteArrayComparators.LEXICOGRAPHIC.compare(
            new byte[] {(byte) 0x80}, new byte[] {(byte) 0x7F});
    assertTrue(result > 0);
  }

  @Test
  void usedAsTreeMapKey_distinctKeysAreIndependent() {
    final TreeMap<byte[], String> map = new TreeMap<>(ByteArrayComparators.LEXICOGRAPHIC);
    map.put(new byte[] {1, 2}, "short");
    map.put(new byte[] {1, 2, 3}, "long");

    assertEquals("short", map.get(new byte[] {1, 2}));
    assertEquals("long", map.get(new byte[] {1, 2, 3}));
    assertNotNull(map.get(new byte[] {1, 2}));
    assertNotNull(map.get(new byte[] {1, 2, 3}));
  }
}
