package com.petwal.engine;

import static java.util.Comparator.comparing;

import com.petwal.api.KeyValueStore;
import com.petwal.engine.memtable.MemTable;
import com.petwal.engine.sstable.SSTable;
import com.petwal.engine.sstable.SSTableWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LSM-tree inspired key-value storage engine.
 *
 * <h3>Write path</h3>
 *
 * <p>Writes go to the in-memory {@link com.petwal.engine.memtable.MemTable}. When the MemTable
 * exceeds {@code memtableMaxSize} bytes it is flushed to a new SSTable on disk. When three or more
 * SSTables accumulate they are merged into a single SSTable (compaction).
 *
 * <h3>Read path</h3>
 *
 * <p>The MemTable is checked first, then each SSTable in newest-first order. The search stops at
 * the first non-{@link Lookup#absent()} result so that a tombstone in a newer layer correctly
 * shadows an older value.
 *
 * <h3>Thread safety</h3>
 *
 * <p>All public methods are {@code synchronized} on {@code this}. {@link
 * com.petwal.engine.sstable.SSTable} instances are NOT independently thread-safe and rely on this
 * coarse lock for serialisation.
 *
 * <h3>Crash safety</h3>
 *
 * <p>There is no WAL. Writes buffered in the MemTable are lost on a crash. Flushed SSTables are
 * crash-safe because they are written with {@code CREATE_NEW} and {@code FileChannel.force(true)}.
 */
public final class LsmStore implements KeyValueStore {

  private final Path directory;
  private MemTable memTable;
  private List<SSTable> ssTables; // index 0 = newest
  private int currentSSTId;
  private final int memtableMaxSize;

  public LsmStore(final Path directory, final int memtableMaxSize) throws IOException {
    this.directory = directory;
    this.memTable = new MemTable();
    this.ssTables = new ArrayList<>();
    this.currentSSTId = 1;
    this.memtableMaxSize = memtableMaxSize;
    Files.createDirectories(directory);
    loadExistingSSTables();
  }

  @Override
  public synchronized void put(final byte[] key, final byte[] value) throws IOException {
    memTable.put(Arrays.copyOf(key, key.length), value);
    if (shouldFlush()) {
      flush();
    }
    if (shouldMerge()) {
      merge();
    }
  }

  @Override
  public synchronized byte[] get(final byte[] key) throws IOException {
    Lookup result = memTable.get(key);
    if (result.hasValueOrTombstone()) {
      return result.value();
    }

    for (final SSTable tableReader : ssTables) {
      result = tableReader.get(key);
      if (result.hasValueOrTombstone()) {
        return result.value();
      }
    }
    return null;
  }

  @Override
  public synchronized void delete(final byte[] key) throws IOException {
    memTable.delete(Arrays.copyOf(key, key.length));
    if (shouldFlush()) {
      flush();
    }
    if (shouldMerge()) {
      merge();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    for (final SSTable sst : ssTables) {
      sst.close();
    }
  }

  public synchronized void flush() throws IOException {
    if (memTable.isEmpty()) {
      return;
    }
    final Path sstPath = directory.resolve(String.format("sst-%05d.sst", currentSSTId++));
    final SSTable reader = SSTableWriter.flush(memTable, sstPath);
    ssTables.addFirst(reader); // prepend: newest first
    memTable = new MemTable();
  }

  private synchronized void merge() throws IOException {
    final String fileName = String.format("sst-%05d.sst", currentSSTId++);
    ssTables = List.of(SSTableWriter.merge(ssTables, directory.resolve(fileName)));
  }

  private void loadExistingSSTables() throws IOException {
    final List<Path> sstFilePaths = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "sst-*.sst")) {
      for (final Path path : stream) {
        sstFilePaths.add(path);
      }
    }
    // Sort descending (newest first)
    sstFilePaths.sort(comparing((Path path) -> path.getFileName().toString()).reversed());

    int maxId = 0;
    for (final Path path : sstFilePaths) {
      ssTables.add(SSTable.open(path));
      maxId = Math.max(maxId, parseId(path));
    }
    currentSSTId = maxId + 1;
  }

  private static int parseId(Path path) {
    final String name = path.getFileName().toString();
    return Integer.parseInt(name.substring(4, name.length() - 4));
  }

  private boolean shouldFlush() {
    return memTable.getSizeBytes() > memtableMaxSize;
  }

  private boolean shouldMerge() {
    return ssTables.size() >= 3;
  }
}
