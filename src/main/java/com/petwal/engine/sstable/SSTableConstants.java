package com.petwal.engine.sstable;

public class SSTableConstants {

  /**
   * Magic number used to validate SSTable files. Format in file: {@code [magic:8]} (8 bytes)
   * Typically "SSTABLE\0" in ASCII.
   */
  public static final long MAGIC_VALUE = 0x5353_5441_424C_4500L;

  /** Size of the magic number in bytes. Matches {@code [magic:8]} in the file footer. */
  public static final long MAGIC_SIZE = 8L;

  /**
   * Number of bytes used to store a key length in the index. Format in file: {@code [keyLen:4]} (4
   * bytes)
   */
  public static final long INDEX_KEY_LENGTH = 4L;

  /**
   * Number of bytes used to store a data offset in the index. Format in file: {@code
   * [dataOffset:8]} (8 bytes)
   */
  public static final long OFFSET_LENGTH = 8L;

  /**
   * Total size of the SSTable footer in bytes. Format in file: {@code
   * [bloomOffset:8][indexOffset:8][magic:8]} (24 bytes)
   */
  public static final long FOOTER_LENGTH = 24L;

  /** Size in bytes of the fixed record prefix: {@code [keyLen:4][valueLen:4]}. */
  public static final int RECORD_HEADER_SIZE = 8;

  /** Value written for tombstone (delete) records. */
  public static final int TOMBSTONE = -1;

  /** Value stored for tombstone (delete) records. (empty bytes) */
  public static final byte[] TOMBSTONE_BYTES = new byte[0];
}
