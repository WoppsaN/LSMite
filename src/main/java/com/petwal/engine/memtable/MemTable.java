package com.petwal.engine.memtable;

import com.petwal.engine.Lookup;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * In-memory write buffer, sorted by unsigned-byte key order.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Tombstone sentinel</b>: a fixed {@code private static final byte[0]} reference
 *       stored as the map value. A single {@code map.get} + reference-equality check
 *       distinguishes absent (null), deleted (sentinel), and present (any other array)
 *       in O(log n).</li>
 *   <li><b>Size accounting</b>: conservative overcount — overwrites add new bytes without
 *       subtracting old. O(1) per write; may flush slightly early, never late.</li>
 *   <li><b>Flush threshold</b>: 4 MiB.</li>
 * </ul>
 */
public final class MemTable {

    public static final int FLUSH_THRESHOLD = 4 * 1024 * 1024; // 4 MiB

    private static final byte[] TOMBSTONE_BYTES = new byte[0];
    private static final Comparator<byte[]> KEY_ORDER = (a, b) -> {
        final int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0){
                return diff;
            }
        }
        return a.length - b.length;
    };

    private final TreeMap<byte[], byte[]> map = new TreeMap<>(KEY_ORDER);
    private int sizeBytes = 0;

    public void put(final byte[] key, final byte[] value) {
        map.put(key, value);
        sizeBytes += key.length + value.length;
    }

    public void delete(final byte[] key) {
        map.put(key, TOMBSTONE_BYTES);
        sizeBytes += key.length;
    }

    public Lookup get(final byte[] key) {
        final byte[] val = map.get(key);
        if (val == null){
            return Lookup.absent();
        }
        if (val == TOMBSTONE_BYTES) {
            return Lookup.deleted();
        }
        return Lookup.found(val);
    }

    public boolean shouldFlush() {
        return sizeBytes >= FLUSH_THRESHOLD;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** Sorted entries for flushing. Tombstone sentinel converted to {@code null} value. */
    public Iterable<Entry> sortedEntries() {
        return () -> map.entrySet().stream()
                .map(e -> new Entry(e.getKey(), e.getValue() == TOMBSTONE_BYTES ? null : e.getValue()))
                .iterator();
    }

    public void clear() {
        map.clear();
        sizeBytes = 0;
    }
}
