package com.petwal.engine.sstable;

public class SSTableConstants {

    static final long MAGIC = 0x5353_5441_424C_4500L;

    /** Size in bytes of the fixed record prefix: {@code [keyLen:4][valueLen:4]}. */
    static final int RECORD_HEADER_SIZE = 8;

    /** Value written for tombstone (delete) records. */
    static final int TOMBSTONE = -1;

}
