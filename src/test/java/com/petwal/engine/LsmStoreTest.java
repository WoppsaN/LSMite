package com.petwal.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LsmStoreTest {

    @TempDir
    private Path dir;

    private LsmStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new LsmStore(dir);
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
    }

    // 1. Basic put + get (MemTable only)
    @Test
    void putAndGet_memTableOnly() throws IOException {
        store.put(toBytes("hello"), toBytes("world"));
        assertArrayEquals(toBytes("world"), store.get(toBytes("hello")));
    }

    // 2. Get absent key returns null
    @Test
    void get_absentKey_returnsNull() throws IOException {
        assertNull(store.get(toBytes("missing")));
    }

    // 3. Put overwrites in MemTable
    @Test
    void put_overwrites_inMemTable() throws IOException {
        store.put(toBytes("k"), toBytes("v1"));
        store.put(toBytes("k"), toBytes("v2"));
        assertArrayEquals(toBytes("v2"), store.get(toBytes("k")));
    }

    // 4. Delete removes from MemTable
    @Test
    void delete_removesFromMemTable() throws IOException {
        store.put(toBytes("k"), toBytes("v"));
        store.delete(toBytes("k"));
        assertNull(store.get(toBytes("k")));
    }

    // 5. Persistence: flush + close + reopen → readable from SSTable
    @Test
    void flush_closeReopen_readableFromSSTable() throws IOException {
        store.put(toBytes("persisted"), toBytes("value"));
        store.flush();
        store.close();
        try (LsmStore s2 = new LsmStore(dir)) {
            assertArrayEquals(toBytes("value"), s2.get(toBytes("persisted")));
        }
    }

    // 6. Tombstone in MemTable hides older SSTable value
    @Test
    void tombstone_inMemTable_hidesOlderSSTableValue() throws IOException {
        store.put(toBytes("k"), toBytes("old"));
        store.flush();
        store.delete(toBytes("k"));
        assertNull(store.get(toBytes("k")));
    }

    // 7. Tombstone in SSTable hides value in older SSTable (reopen)
    @Test
    void tombstone_inSSTable_hidesOlderSSTable_afterReopen() throws IOException {
        store.put(toBytes("k"), toBytes("old"));
        store.flush();
        store.delete(toBytes("k"));
        store.flush();
        store.close();
        try (LsmStore s2 = new LsmStore(dir)) {
            assertNull(s2.get(toBytes("k")));
        }
    }

    // 8. Newer MemTable value supersedes SSTable value
    @Test
    void newerMemTableValue_supersedesSSTable() throws IOException {
        store.put(toBytes("k"), toBytes("old"));
        store.flush();
        store.put(toBytes("k"), toBytes("new"));
        assertArrayEquals(toBytes("new"), store.get(toBytes("k")));
    }

    // 9. Read through multiple SSTables (different keys in each)
    @Test
    void readThrough_multipleSSTables() throws IOException {
        store.put(toBytes("a"), toBytes("1"));
        store.flush();
        store.put(toBytes("b"), toBytes("2"));
        store.flush();
        assertArrayEquals(toBytes("1"), store.get(toBytes("a")));
        assertArrayEquals(toBytes("2"), store.get(toBytes("b")));
    }

    // 10. Binary keys survive flush round-trip
    @Test
    void binaryKeys_surviveFlushRoundTrip() throws IOException {
        final byte[] key = {0x00, (byte) 0xFF, 0x7F};
        final byte[] value = {0x01, 0x02, 0x03};
        store.put(key, value);
        store.flush();
        assertArrayEquals(value, store.get(key));
    }

    // 11. Bloom filter — no false negatives (100 keys, all found after flush)
    @Test
    void bloomFilter_noFalseNegatives() throws IOException {
        final byte[][] keys = new byte[100][];
        for (int i = 0; i < 100; i++) {
            keys[i] = toBytes("key" + i);
            store.put(keys[i], toBytes("val" + i));
        }
        store.flush();
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(toBytes("val" + i), store.get(keys[i]), "Missing: key" + i);
        }
    }

    // 12. Empty value (new byte[0]) is valid through flush
    @Test
    void emptyValue_validThroughFlush() throws IOException {
        store.put(toBytes("empty"), new byte[0]);
        store.flush();
        final byte[] result = store.get(toBytes("empty"));
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
