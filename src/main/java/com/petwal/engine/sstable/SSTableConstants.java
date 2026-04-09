package com.petwal.engine.sstable;

/**
 * Compile-time constants that define the SSTable binary file format.
 *
 * <h3>File layout (in order)</h3>
 *
 * <pre>
 * [magic:8]        — identifies the file as a valid SSTable
 * [data section]   — sorted records: [keyLen:4][valueLen:4][key][value]
 * [bloom section]  — serialised Bloom filter
 * [index section]  — [numEntries:4] followed by per-key: [keyLen:4][key][dataOffset:8]
 * [footer:24]      — [bloomOffset:8][indexOffset:8][magic:8]
 * </pre>
 *
 * <h3>Tombstone encoding</h3>
 *
 * <p>On disk, a deleted key is encoded with {@code valueLen == TOMBSTONE} (-1) and no value bytes.
 * In the MemTable, the in-memory sentinel is {@code TOMBSTONE_BYTES} (an empty byte array), which
 * is converted to a {@code null} value when producing {@link com.petwal.engine.memtable.Entry}
 * objects for the flush path.
 */
public final class SSTableConstants {

  private SSTableConstants() {}

  /** Magic number written at the start and end of every SSTable file ({@code "SSTABLE\0"}). */
  public static final long MAGIC_VALUE = 0x5353_5441_424C_4500L;

  /** Byte size of the magic number field (one {@code long}). */
  public static final long MAGIC_SIZE = 8L;

  /**
   * Byte size of every 4-byte length field in the format: {@code keyLen}, {@code valueLen}, and
   * {@code numEntries}.
   */
  public static final int LENGTH_SIZE = Integer.BYTES;

  /**
   * Byte size of every 8-byte offset field in the format: {@code dataOffset}, {@code bloomOffset},
   * and {@code indexOffset}.
   */
  public static final int OFFSET_SIZE = Long.BYTES;

  /** Byte size of the record header: {@code keyLen:4 + valueLen:4}. */
  public static final int RECORD_HEADER_SIZE = 8;

  /** Byte size of the footer section: {@code bloomOffset:8 + indexOffset:8 + magic:8}. */
  public static final int FOOTER_SIZE = 24;

  /** On-disk sentinel value for {@code valueLen} that marks a deleted key. */
  public static final int TOMBSTONE = -1;

  /**
   * In-memory sentinel stored in the MemTable's {@code TreeMap} for deleted keys. Using an empty
   * byte array (rather than {@code null}) keeps the map from losing the tombstone entry.
   */
  public static final byte[] TOMBSTONE_BYTES = new byte[0];
}
