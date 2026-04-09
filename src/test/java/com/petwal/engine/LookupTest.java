package com.petwal.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Lookup}.
 *
 * <p>Verifies the three-valued semantics (ABSENT / DELETED / FOUND), singleton identity for the
 * constant states, and that an empty-value FOUND result is not confused with a tombstone.
 */
final class LookupTest {

  @Test
  void absent_hasValueOrTombstone_returnsFalse() {
    assertFalse(Lookup.absent().hasValueOrTombstone());
  }

  @Test
  void absent_value_returnsNull() {
    assertNull(Lookup.absent().value());
  }

  @Test
  void absent_isSingleton() {
    assertSame(Lookup.absent(), Lookup.absent());
  }

  @Test
  void deleted_hasValueOrTombstone_returnsTrue() {
    assertTrue(Lookup.deleted().hasValueOrTombstone());
  }

  @Test
  void deleted_value_returnsNull() {
    assertNull(Lookup.deleted().value());
  }

  @Test
  void deleted_isSingleton() {
    assertSame(Lookup.deleted(), Lookup.deleted());
  }

  @Test
  void found_hasValueOrTombstone_returnsTrue() {
    assertTrue(Lookup.found(new byte[] {1, 2, 3}).hasValueOrTombstone());
  }

  @Test
  void found_value_returnsCorrectBytes() {
    final byte[] value = new byte[] {4, 5, 6};
    assertArrayEquals(value, Lookup.found(value).value());
  }

  @Test
  void found_emptyValue_isNotTombstone() {
    final Lookup result = Lookup.found(new byte[0]);
    assertTrue(result.hasValueOrTombstone());
    assertNotNull(result.value());
    assertEquals(0, result.value().length);
  }

  @Test
  void found_createsNewInstanceEachCall() {
    final byte[] value = new byte[] {1};
    assertNotSame(Lookup.found(value), Lookup.found(value));
  }
}
