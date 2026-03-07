package com.petwal.engine.memtable;

import com.petwal.engine.Lookup;
import com.petwal.engine.sstable.SSTableConstants;
import java.util.Arrays;
import java.util.Comparator;
import java.util.TreeMap;

/**
 * In-memory write buffer, sorted by unsigned-byte key order.
 *
 * <h3>Design decisions</h3>
 *
 * <ul>
 *   <li><b>Tombstone sentinel</b>: a fixed {@code private static final byte[0]} reference stored as
 *       the map value. A single {@code map.get} + reference-equality check distinguishes absent
 *       (null), deleted (sentinel), and present (any other array) in O(log n).
 *   <li><b>Size accounting</b>: conservative overcount — overwrites add new bytes without
 *       subtracting old. O(1) per write; may flush slightly early, never late.
 *   <li><b>Flush threshold</b>: 4 MiB.
 * </ul>
 */
public final class MemTable {

  public static final Comparator<byte[]> BYTE_ARRAY_COMPARATOR =
      (a, b) -> {
        final int cmp = Arrays.compareUnsigned(a, b);
        if (cmp != 0) {
          return cmp;
        }
        return a.length - b.length;
      };

  private final TreeMap<byte[], byte[]> treeMap;
  private int sizeBytes;

  public MemTable() {
    this.treeMap = new TreeMap<>(BYTE_ARRAY_COMPARATOR);
    this.sizeBytes = 0;
  }

  public void put(final byte[] key, final byte[] value) {
    treeMap.put(key, value);
    sizeBytes += key.length + value.length;
  }

  public void delete(final byte[] key) {
    treeMap.put(key, SSTableConstants.TOMBSTONE_BYTES);
    sizeBytes += key.length;
  }

  public Lookup get(final byte[] key) {
    final byte[] val = treeMap.get(key);
    if (val == null) {
      return Lookup.absent();
    }
    if (val == SSTableConstants.TOMBSTONE_BYTES) {
      return Lookup.deleted();
    }
    return Lookup.found(val);
  }

  public boolean isEmpty() {
    return treeMap.isEmpty();
  }

  public Iterable<Entry> entries() {
    return () ->
        treeMap.entrySet().stream()
            .map(
                e ->
                    new Entry(
                        e.getKey(),
                        e.getValue() == SSTableConstants.TOMBSTONE_BYTES ? null : e.getValue()))
            .iterator();
  }

  public int getSizeBytes() {
    return sizeBytes;
  }
}
