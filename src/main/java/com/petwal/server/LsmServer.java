package com.petwal.server;

import com.petwal.engine.LsmStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TCP server exposing {@link LsmStore} over a line-based text protocol.
 *
 * <h3>Protocol</h3>
 *
 * <pre>
 *   PUT  &lt;key&gt; &lt;value&gt;\n  →  OK\n
 *   GET  &lt;key&gt;\n          →  &lt;value&gt;\n  or  NOT_FOUND\n
 *   DELETE &lt;key&gt;\n        →  OK\n
 *   (any error)           →  ERROR &lt;message&gt;\n
 * </pre>
 *
 * <h3>Shutdown sequence</h3>
 *
 * {@link #stop()} signals the accept loop, closes the server socket, drains in-flight handlers,
 * flushes the MemTable, then closes the store.
 */
public final class LsmServer {

  private final LsmStore store;
  private final int port;
  private final int threadPoolSize;

  private volatile boolean running;
  private ServerSocket serverSocket;
  private ExecutorService executor;
  private Thread acceptThread;

  public LsmServer(final LsmStore store, final int port, final int threadPoolSize) {
    this.store = store;
    this.port = port;
    this.threadPoolSize = threadPoolSize;
  }

  public void start(final String dataDir, final int memtableSize) throws IOException {
    serverSocket = new ServerSocket(port);
    executor = Executors.newFixedThreadPool(threadPoolSize);
    running = true;

    System.out.println("[LsmServer] Listening on port " + port);
    System.out.println("[LsmServer] Data store in directory " + dataDir);
    System.out.println("[LsmServer] Memtable size set to " + memtableSize);

    acceptThread = new Thread(this::acceptLoop, "lsm-accept");
    acceptThread.setDaemon(false); // keeps JVM alive until stop()
    acceptThread.start();
  }

  private void acceptLoop() {
    while (running) {
      try {
        final Socket clientSocket = serverSocket.accept();
        executor.submit(new ClientHandler(clientSocket, store));
      } catch (SocketException e) {
        if (running) {
          System.err.println("[LsmServer] Unexpected accept error: " + e.getMessage());
        }
        break; // stop() closed the socket — normal exit path
      } catch (IOException e) {
        System.err.println("[LsmServer] Accept error (continuing): " + e.getMessage());
      }
    }
  }

  public void stop() throws Exception {
    running = false;
    if (serverSocket != null) {
      serverSocket.close();
    }
    if (acceptThread != null) {
      acceptThread.join(5_000);
    }
    if (executor != null) {
      executor.shutdown();
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) { // drain in-flight handlers
        executor.shutdownNow();
      }
    }
    store.flush(); // persist MemTable (no more writes possible)
    store.close(); // close SSTable FileChannels
    System.out.println("[LsmServer] Shutdown complete.");
  }

  /** Blocks until the accept thread exits (i.e. until {@link #stop()} is called). */
  public void awaitTermination() throws InterruptedException {
    if (acceptThread != null) {
      acceptThread.join();
    }
  }
}
