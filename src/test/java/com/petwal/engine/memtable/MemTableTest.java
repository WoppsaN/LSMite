package com.petwal.engine.memtable;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petwal.engine.Lookup;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemTable}.
 *
 * <p>Covers the put/delete/get lifecycle, tombstone sentinel identity (the empty-byte-array
 * sentinel must not be confused with a legitimately stored empty value), size accounting, and the
 * guarantee that {@link MemTable#entries()} returns keys in lexicographic order.
 */
final class MemTableTest {

  private MemTable table;

  @BeforeEach
  void setUp() {
    table = new MemTable();
  }

  private static byte[] toBytes(final String s) {
    return s.getBytes(UTF_8);
  }

  // -------------------------------------------------------------------------
  // get / put / delete semantics
  // -------------------------------------------------------------------------

  @Test
  void get_absentKey_returnsAbsent() {
    final Lookup result = table.get(toBytes("missing"));
    assertFalse(result.hasValueOrTombstone());
    assertNull(result.value());
  }

  @Test
  void put_thenGet_returnsFound() {
    table.put(toBytes("k"), toBytes("v"));
    final Lookup result = table.get(toBytes("k"));
    assertTrue(result.hasValueOrTombstone());
    assertArrayEquals(toBytes("v"), result.value());
  }

  @Test
  void put_overwrite_returnsNewestValue() {
    table.put(toBytes("k"), toBytes("v1"));
    table.put(toBytes("k"), toBytes("v2"));
    assertArrayEquals(toBytes("v2"), table.get(toBytes("k")).value());
  }

  @Test
  void delete_afterPut_returnsDeleted() {
    table.put(toBytes("k"), toBytes("v"));
    table.delete(toBytes("k"));
    final Lookup result = table.get(toBytes("k"));
    assertTrue(result.hasValueOrTombstone());
    assertNull(result.value());
  }

  @Test
  void delete_withoutPriorPut_returnsDeleted() {
    table.delete(toBytes("k"));
    final Lookup result = table.get(toBytes("k"));
    assertTrue(result.hasValueOrTombstone());
    assertNull(result.value());
  }

  @Test
  void put_afterDelete_returnsFound() {
    table.delete(toBytes("k"));
    table.put(toBytes("k"), toBytes("v"));
    final Lookup result = table.get(toBytes("k"));
    assertTrue(result.hasValueOrTombstone());
    assertArrayEquals(toBytes("v"), result.value());
  }

  @Test
  void get_emptyValueBytes_returnsFoundNotTombstone() {
    // Explicitly stored empty value must NOT be treated as a tombstone.
    // Guards against content-equality being used instead of reference identity
    // when checking the TOMBSTONE_BYTES sentinel.
    table.put(toBytes("k"), new byte[0]);
    final Lookup result = table.get(toBytes("k"));
    assertTrue(result.hasValueOrTombstone());
    assertNotNull(result.value());
    assertEquals(0, result.value().length);
  }

  // -------------------------------------------------------------------------
  // isEmpty
  // -------------------------------------------------------------------------

  @Test
  void isEmpty_freshTable_returnsTrue() {
    assertTrue(table.isEmpty());
  }

  @Test
  void isEmpty_afterPut_returnsFalse() {
    table.put(toBytes("k"), toBytes("v"));
    assertFalse(table.isEmpty());
  }

  // -------------------------------------------------------------------------
  // getSizeBytes
  // -------------------------------------------------------------------------

  @Test
  void getSizeBytes_fresh_returnsZero() {
    assertEquals(0, table.getSizeBytes());
  }

  @Test
  void getSizeBytes_afterPut_accumulatesKeyAndValueLengths() {
    table.put(toBytes("ab"), toBytes("xyz")); // 2 + 3 = 5
    assertEquals(5, table.getSizeBytes());
  }

  @Test
  void getSizeBytes_afterDelete_accumulatesKeyLengthOnly() {
    table.delete(toBytes("abc")); // 3, no value bytes
    assertEquals(3, table.getSizeBytes());
  }

  @Test
  void getSizeBytes_overwrite_countsEachWriteSeparately() {
    // Size accounting intentionally overcounts on overwrite.
    table.put(toBytes("k"), toBytes("v1")); // 1 + 2 = 3
    table.put(toBytes("k"), toBytes("v2")); // 1 + 2 = 3 again
    assertEquals(6, table.getSizeBytes());
  }

  // -------------------------------------------------------------------------
  // entries()
  // -------------------------------------------------------------------------

  @Test
  void entries_emptyTable_isEmpty() {
    assertFalse(table.entries().iterator().hasNext());
  }

  @Test
  void entries_singlePut_yieldsOneNonTombstoneEntry() {
    table.put(toBytes("k"), toBytes("v"));
    final List<Entry> entries = toList(table.entries());
    assertEquals(1, entries.size());
    assertArrayEquals(toBytes("k"), entries.get(0).key());
    assertFalse(entries.get(0).isTombstone());
    assertArrayEquals(toBytes("v"), entries.get(0).value());
  }

  @Test
  void entries_delete_yieldsEntryWithIsTombstoneTrue() {
    table.delete(toBytes("k"));
    final List<Entry> entries = toList(table.entries());
    assertEquals(1, entries.size());
    assertTrue(entries.get(0).isTombstone());
    assertNull(entries.get(0).value());
  }

  @Test
  void entries_multipleKeys_sortedLexicographically() {
    table.put(toBytes("c"), toBytes("3"));
    table.put(toBytes("a"), toBytes("1"));
    table.put(toBytes("b"), toBytes("2"));
    final List<Entry> entries = toList(table.entries());
    assertEquals(3, entries.size());
    assertArrayEquals(toBytes("a"), entries.get(0).key());
    assertArrayEquals(toBytes("b"), entries.get(1).key());
    assertArrayEquals(toBytes("c"), entries.get(2).key());
  }

  @Test
  void entries_mixedPutsAndDeletes_producesCorrectEntries() {
    table.put(toBytes("a"), toBytes("1"));
    table.delete(toBytes("b"));
    final List<Entry> entries = toList(table.entries());
    assertEquals(2, entries.size());
    assertFalse(entries.get(0).isTombstone()); // "a"
    assertTrue(entries.get(1).isTombstone()); // "b"
  }

  private static List<Entry> toList(final Iterable<Entry> iterable) {
    final List<Entry> list = new ArrayList<>();
    for (final Entry entry : iterable) {
      list.add(entry);
    }
    return list;
  }
}
