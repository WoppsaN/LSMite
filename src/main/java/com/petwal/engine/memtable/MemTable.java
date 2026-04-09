package com.petwal.engine.memtable;

import com.petwal.comparator.ByteArrayComparators;
import com.petwal.engine.Lookup;
import com.petwal.engine.sstable.SSTableConstants;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory sorted write buffer backed by a {@link java.util.TreeMap}.
 *
 * <h3>Size accounting</h3>
 *
 * <p>The byte count ({@link #getSizeBytes()}) is a conservative overcount: it sums raw key and
 * value lengths without tree node overhead. This may cause a flush slightly earlier than the exact
 * threshold.
 *
 * <h3>Tombstone sentinel</h3>
 *
 * <p>Deleted keys are stored in the {@code TreeMap} with the value {@link
 * com.petwal.engine.sstable.SSTableConstants#TOMBSTONE_BYTES} (an empty byte array). This preserves
 * the key's presence in the map so that subsequent reads find the tombstone before descending to
 * SSTables. The sentinel is converted to a {@code null} value when producing {@link Entry} objects
 * for the flush path.
 */
public final class MemTable {

  private final TreeMap<byte[], byte[]> treeMap;
  private int sizeBytes;

  public MemTable() {
    this.treeMap = new TreeMap<>(ByteArrayComparators.LEXICOGRAPHIC);
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
    final byte[] value = treeMap.get(key);
    if (value == null) {
      return Lookup.absent();
    }
    if (value == SSTableConstants.TOMBSTONE_BYTES) {
      return Lookup.deleted();
    }
    return Lookup.found(value);
  }

  public boolean isEmpty() {
    return treeMap.isEmpty();
  }

  public Iterable<Entry> entries() {
    return () -> treeMap.entrySet().stream().map(MemTable::entry).iterator();
  }

  private static Entry entry(Map.Entry<byte[], byte[]> entry) {
    return new Entry(
        entry.getKey(),
        entry.getValue() == SSTableConstants.TOMBSTONE_BYTES ? null : entry.getValue());
  }

  public int getSizeBytes() {
    return sizeBytes;
  }
}
