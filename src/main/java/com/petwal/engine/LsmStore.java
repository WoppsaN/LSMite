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
 *
 *
 * <h3>LSM-tree key-value store.</h3>
 *
 * <ul>
 *   <li><b>No WAL</b>: unflushed MemTable data is lost on crash. SSTables are crash-safe ({@code
 *       CREATE_NEW} + {@code force(true)}).
 *   <li><b>MemTable</b>: bounded 4 MiB in-memory write buffer; auto-flushed on overflow.
 *   <li><b>SSTable list</b>: {@code ssTables.get(0)} = newest; prepended on each flush.
 *   <li><b>SSTable naming</b>: {@code sst-00001.sst} — zero-padded so lexicographic order equals
 *       creation order.
 *   <li><b>nextSSTId</b>: {@code max(parsed ids) + 1} on startup — handles gaps correctly.
 *   <li><b>{@code synchronized(this)}</b>: all public methods; protects MemTable + SSTable list
 *       atomicity.
 * </ul>
 *
 * <h3>Read path</h3>
 *
 * <pre>
 * get(key):
 *   r = memTable.get(key)
 *   if !r.isAbsent() → return r.isTombstone() ? null : r.value()
 *   for sst in ssTables:          // newest first
 *     r = sst.get(key)
 *     if !r.isAbsent() → return r.isTombstone() ? null : r.value()
 *   return null
 * </pre>
 */
public final class LsmStore implements KeyValueStore {

  private final Path directory;
  private MemTable memTable;
  private final List<SSTable> ssTables; // index 0 = newest
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
  }

  @Override
  public synchronized void close() throws IOException {
    for (final SSTable sst : ssTables) {
      sst.close();
    }
  }

  /**
   * Flushes the current MemTable to a new SSTable file. Public so {@link
   * com.petwal.server.LsmServer} can flush on shutdown. Tests in {@code com.petwal.engine} also
   * call this directly.
   */
  public synchronized void flush() throws IOException {
    if (memTable.isEmpty()) {
      return;
    }
    final Path sstPath = directory.resolve(String.format("sst-%05d.sst", currentSSTId++));
    System.out.println("flushing to " + sstPath.getFileName());
    final SSTable reader = SSTableWriter.flush(memTable, sstPath);
    ssTables.addFirst(reader); // prepend: newest first
    memTable = new MemTable();
    System.out.println("flushing done");
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
}
