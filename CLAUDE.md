# LSMite — Claude Context File

## Project

**Name:** LSMite
**Description:** LSM-tree inspired key-value storage engine in pure Java 21, no external libraries
(except Guava for Murmur3 hashing).
**Group:** `com.petwal`
**Java:** 21
**Build:** Gradle 8

---

## Architecture overview

```
KeyValueStore (interface)
        │
        └── LsmStore
               ├── MemTable          (in-memory sorted write buffer, 4 MiB threshold)
               └── List<SSTableReader>   (on-disk immutable sorted segments, newest first)
```

**Write path:** `put`/`delete` → MemTable → auto-flush to new SSTable when ≥ 4 MiB.

**Read path:** MemTable → SSTable[0] → SSTable[1] → … (newest-first layered search, stops on
first non-absent `Lookup` result).

---

## Package structure

```
com.petwal
├── Main.java                              — demo/entry-point
├── api/KeyValueStore.java                 — public interface (put, get, delete, close)
├── engine/LogFormat.java                  — HEADER_SIZE=8, TOMBSTONE=-1; public static helpers
├── engine/Lookup.java                     — three-value result: ABSENT / DELETED / FOUND
├── engine/LsmStore.java                   — core store; flush() is pkg-private for tests
├── engine/memtable/MemTable.java          — TreeMap-backed buffer; FLUSH_THRESHOLD=4MiB
├── engine/memtable/Entry.java             — record(key, value); isTombstone()=value==null
├── engine/sstable/BloomFilter.java        — pkg-private; ~1% FPR, Murmur3-128 double-hashing
├── engine/sstable/SSTableWriter.java      — static flush(MemTable, Path) → SSTableReader
└── engine/sstable/SSTableReader.java      — open(Path), get(key) with dense index + bloom guard
```

---

## SSTable file format

```
Offset (relative)   Field
──────────────────  ──────────────────────────────────────────────────
0                   [magic:8]       0x5353_5441_424C_4500L ("SSTABLE\0")

[Data section]      sorted records, one per key:
                    [keyLen:4][valueLen:4][key:keyLen][value:valueLen]
                    valueLen = -1 (TOMBSTONE) for deletes; no value bytes written

[Bloom section]     [numBits:4][k:1][bitArray: ceil(numBits/8) bytes]

[Index section]     [numEntries:4]
                    per entry: [keyLen:4][key:keyLen][dataOffset:8]
                    dataOffset = byte offset of the record's keyLen field in data section

[Footer:24]         [bloomOffset:8][indexOffset:8][magic:8]
```

**Record format summary**

| Operation | Encoding |
|-----------|----------|
| PUT  | `[keyLen:4][valueLen ≥ 0:4][key][value]` |
| DELETE | `[keyLen:4][valueLen = -1:4][key]` (no value bytes) |

---

## Key design decisions

1. **No WAL** — unflushed MemTable data is lost on crash. SSTables written with `CREATE_NEW` +
   `FileChannel.force(true)` are crash-safe (data + metadata flushed before the file is visible
   in the index).

2. **Tombstone sentinel** — `Entry.isTombstone()` is `value == null`. The `MemTable` TreeMap
   stores `null` values for deleted keys so a subsequent `get` finds the tombstone in the
   in-memory layer before descending to SSTables.

3. **`Lookup` three-value type** — `ABSENT` / `DELETED` / `FOUND`. The layered read stops at
   the first non-`ABSENT` result, correctly handling deletes that shadow older SSTables.

4. **Dense index** — one index entry per key, storing the **record-start offset** (the position
   of `keyLen` in the data section). Avoids any arithmetic; random-access read reconstructs
   the record from that offset.

5. **Two-pass SSTable write** — first pass computes all section offsets and sizes, second pass
   writes a single `ByteBuffer` with `CREATE_NEW` + `force(true)`. Prevents partial files from
   being visible.

6. **Bloom filter** — classic Bloom filter with Murmur3-128 double-hashing; `k` and `numBits`
   chosen for ~1% FPR at construction time. Checked before the index lookup on reads.

7. **`synchronized(this)` on all public `LsmStore` methods** — protects the MemTable +
   SSTable list atomicity. `SSTableReader` is NOT independently thread-safe (uses stateful
   `FileChannel.position()`); it relies on `LsmStore`'s lock.

8. **SSTable list ordering** — `ssTables.get(0)` is always the newest SSTable (prepended on
   each flush). Read path iterates newest-first to honour the most recent write.

9. **File naming** — `sst-00001.sst` zero-padded to 5 digits so lexicographic order equals
   creation order. On startup, `nextSSTId = max(parsed ids) + 1` to handle gaps correctly.

---

## Known limitations

- **No WAL** — crash after `put` but before `flush()` loses that write.
- **No compaction** — SSTables accumulate forever; read amplification grows with write volume.
- **MemTable size accounting** — conservative overcount (key + value length, no tree overhead);
  can flush slightly early.
- **`SSTableReader` not independently thread-safe** — relies on `LsmStore`'s coarse lock.

---

## Build & test

```bash
./gradlew compileJava         # compile only
./gradlew cleanTest test      # run all 12 tests (LsmStoreTest)
./gradlew run                 # run Main demo
```

---

## Dependencies

| Scope | Artifact |
|-------|----------|
| compile | `com.google.guava:guava:33.3.1-jre` (Murmur3 hashing only) |
| test   | `org.junit.jupiter:junit-jupiter` via `junit-bom:5.10.0` |
