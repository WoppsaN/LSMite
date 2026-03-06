# LSMite

A log-structured merge-tree (LSM-tree) key-value storage engine written in pure Java 21.
No frameworks, no external dependencies except Guava for Murmur3 hashing.

---

## Features

- **Durable writes** — SSTables written atomically with `CREATE_NEW` + `force(true)`
- **In-memory write buffer** — `MemTable` backed by a `TreeMap`; auto-flushes at 4 MiB
- **Bloom filter** — ~1% false-positive rate guards every SSTable read
- **Dense index** — one index entry per key; O(1) random access to any record
- **Tombstone deletes** — deletes propagate correctly across MemTable and all SSTable layers
- **Startup recovery** — existing SSTables on disk are loaded automatically on open
- **Thread-safe store** — all public `LsmStore` methods are `synchronized`

---

## Architecture

Writes go to an in-memory `MemTable`. When it reaches 4 MiB the contents are sorted and
flushed to an immutable SSTable file on disk. Reads check the MemTable first, then each
SSTable from newest to oldest, stopping at the first definitive result (found or deleted).

```
put / delete
    │
    ▼
MemTable (TreeMap, in-memory)
    │ auto-flush at 4 MiB
    ▼
SSTableWriter ──► sst-00001.sst
                  sst-00002.sst
                  ...
get
    │
    ├─► MemTable.get(key)          ← check newest writes first
    ├─► SSTableReader[0].get(key)  ← newest SSTable
    ├─► SSTableReader[1].get(key)
    └─► ... (stops on first hit)
```

---

## Build & Run

**Prerequisites:** JDK 21, Gradle wrapper included.

```bash
# Compile
./gradlew compileJava

# Run all tests (12 tests)
./gradlew cleanTest test

# Run the demo
./gradlew run
```

---

## Usage example

```java
Path dbDir = Path.of("my-store");

try (KeyValueStore store = new LsmStore(dbDir)) {
    // Write
    store.put("user:1".getBytes(), "Alice".getBytes());
    store.put("user:2".getBytes(), "Bob".getBytes());

    // Read
    byte[] value = store.get("user:1".getBytes());
    System.out.println(new String(value)); // Alice

    // Overwrite
    store.put("user:1".getBytes(), "Alicia".getBytes());

    // Delete
    store.delete("user:2".getBytes());
    System.out.println(store.get("user:2".getBytes())); // null
}
```

Existing SSTables in `dbDir` are loaded automatically on the next `new LsmStore(dbDir)`.

---

## SSTable format

Each SSTable is a single file with four sections:

```
[magic:8]       "SSTABLE\0" — identifies file type
[data]          sorted records: [keyLen:4][valueLen:4][key][value]
                valueLen = -1 signals a tombstone (delete marker); no value bytes follow
[bloom]         [numBits:4][k:1][bitArray] — Bloom filter for fast negative lookups
[index]         [numEntries:4] then per-key: [keyLen:4][key][offset:8]
                offset points to the record's start in the data section
[footer:24]     [bloomOffset:8][indexOffset:8][magic:8]
```

Reading a key: check the Bloom filter → binary-search the index → seek to the record offset
→ compare full key → return value (or tombstone).

---

## Limitations

- **No write-ahead log (WAL)** — unflushed MemTable data is lost on crash
- **No compaction** — obsolete SSTables are never merged or purged; read amplification grows
  linearly with write volume
- **Single-threaded writes** — the store uses a coarse `synchronized` lock; concurrent writes
  are serialised


---

## License

MIT — see [LICENSE](LICENSE) (placeholder).
