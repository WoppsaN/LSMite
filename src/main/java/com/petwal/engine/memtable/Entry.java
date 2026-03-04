package com.petwal.engine.memtable;

/** Represents a key-value entry from the MemTable. Tombstone indicated by {@code null} value. */
public record Entry(byte[] key, byte[] value) {

    public boolean isTombstone() {
        return value == null;
    }

}
