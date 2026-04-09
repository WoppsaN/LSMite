package com.petwal;

import com.petwal.engine.LsmStore;
import com.petwal.server.LsmServer;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Application entry point for the LSMite TCP server.
 *
 * <p>Parses optional command-line flags ({@code --port}, {@code --data-dir}, {@code
 * --memtable-size}), constructs an {@link com.petwal.engine.LsmStore}, starts an {@link
 * com.petwal.server.LsmServer}, and registers a JVM shutdown hook that flushes and closes the store
 * on SIGINT / SIGTERM.
 */
public class Main {

  private static final int DEFAULT_PORT = 7777;
  private static final int THREAD_POOL_SIZE = 10;
  private static final String DEFAULT_DATA_DIR = "lsmite-data";
  private static final int DEFAULT_MEMTABLE_SIZE = 20; // 1kb

  private static final String PORT_FLAG = "--port=";
  private static final String DATA_DIR_FLAG = "--data-dir=";
  private static final String MEMTABLE_SIZE_FLAG = "--memtable-size=";

  public static void main(String[] args) throws Exception {
    final int port = getPortArgument(args, PORT_FLAG).map(Integer::valueOf).orElse(DEFAULT_PORT);
    final String dataDir = getPortArgument(args, DATA_DIR_FLAG).orElse(DEFAULT_DATA_DIR);
    final int memtableSize =
        getPortArgument(args, MEMTABLE_SIZE_FLAG)
            .map(Integer::valueOf)
            .orElse(DEFAULT_MEMTABLE_SIZE);

    final LsmStore store = new LsmStore(Path.of(dataDir), memtableSize);
    final LsmServer server = new LsmServer(store, port, THREAD_POOL_SIZE);

    setSigintHook(server);

    server.start(dataDir, memtableSize);
    server.awaitTermination(); // block main thread until stop() is called
  }

  // Invoked on SIGINT (Ctrl+C) or SIGTERM
  private static void setSigintHook(final LsmServer server) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("[Main] Shutdown signal received.");
                  try {
                    server.stop();
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                },
                "lsm-shutdown"));
  }

  private static Optional<String> getPortArgument(String[] args, final String flag) {
    for (final String arg : args) {
      if (arg.startsWith(flag)) {
        return Optional.of(arg.substring(flag.length()));
      }
    }
    return Optional.empty();
  }
}
