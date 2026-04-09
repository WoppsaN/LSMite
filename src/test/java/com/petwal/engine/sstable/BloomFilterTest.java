package com.petwal.engine.sstable;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BloomFilter}.
 *
 * <p>Covers the no-false-negatives guarantee, the ~1% false-positive rate target, and the
 * serialization round-trip.
 */
final class BloomFilterTest {

  private static byte[] toBytes(final String s) {
    return s.getBytes(UTF_8);
  }

  @Test
  void mightContain_afterAdd_returnsTrue() {
    final BloomFilter filter = new BloomFilter(10);
    filter.add(toBytes("hello"));
    assertTrue(filter.mightContain(toBytes("hello")));
  }

  @Test
  void noFalseNegatives_100Keys() {
    final BloomFilter filter = new BloomFilter(100);
    for (int i = 0; i < 100; i++) {
      filter.add(toBytes("key-" + i));
    }
    for (int i = 0; i < 100; i++) {
      assertTrue(filter.mightContain(toBytes("key-" + i)), "False negative for key-" + i);
    }
  }

  @Test
  void falsePositiveRate_10000Keys_belowTwoPercent() {
    final BloomFilter filter = new BloomFilter(10_000);
    for (int i = 0; i < 10_000; i++) {
      filter.add(toBytes("key-" + i));
    }
    int falsePositives = 0;
    for (int i = 0; i < 10_000; i++) {
      if (filter.mightContain(toBytes("probe-" + i))) {
        falsePositives++;
      }
    }
    final double rate = (double) falsePositives / 10_000;
    assertTrue(rate < 0.02, "False positive rate " + rate + " exceeds 2%");
  }

  @Test
  void serialize_deserialize_roundTrip_preservesContainment() {
    final BloomFilter original = new BloomFilter(50);
    for (int i = 0; i < 50; i++) {
      original.add(toBytes("entry-" + i));
    }
    final BloomFilter restored = BloomFilter.deserialize(original.serialize());
    for (int i = 0; i < 50; i++) {
      assertTrue(
          restored.mightContain(toBytes("entry-" + i)),
          "Missing key after deserialization: entry-" + i);
    }
  }

  @Test
  void deserialize_doesNotGainExtraKeys() {
    final BloomFilter original = new BloomFilter(10);
    original.add(toBytes("keyA"));
    final BloomFilter restored = BloomFilter.deserialize(original.serialize());
    // "keyB" was never added; it is very unlikely to be a false positive with n=10
    assertFalse(restored.mightContain(toBytes("keyB_definitely_absent_xyz_123")));
  }

  @Test
  void constructor_zero_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0));
  }

  @Test
  void constructor_negative_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> new BloomFilter(-1));
  }

  @Test
  void add_binaryKey_mightContainReturnsTrueForSameBytes() {
    final BloomFilter filter = new BloomFilter(10);
    final byte[] binaryKey = {0x00, (byte) 0xFF, 0x7F};
    filter.add(binaryKey);
    assertTrue(filter.mightContain(binaryKey));
  }
}
