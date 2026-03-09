package com.petwal.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petwal.engine.LsmStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;

/**
 * Per-connection handler that parses line-oriented text commands and dispatches them to the store.
 *
 * <p>Supported commands: {@code PUT <key> <value>}, {@code GET <key>}, {@code DELETE <key>}.
 * Each command and response is terminated by a newline. Values may contain spaces; the line is
 * split into at most three tokens so that everything after the key is treated as the value.
 */
final class ClientHandler implements Runnable {

  private final Socket socket;
  private final LsmStore store;

  ClientHandler(final Socket socket, final LsmStore store) {
    this.socket = socket;
    this.store = store;
  }

  @Override
  public void run() {
    try (socket;
        final var reader =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
        final var writer =
            new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true)) {
      String line;
      while ((line = reader.readLine()) != null) {
        writer.println(handleCommand(line.strip()));
      }
    } catch (SocketException ignored) {
      // client disconnected mid-read — normal exit
    } catch (IOException e) {
      System.err.println("[ClientHandler] I/O error: " + e.getMessage());
    }
  }

  private String handleCommand(String line) {
    if (line.isEmpty()) {
      return "ERROR empty command";
    }

    // limit=3: ["CMD", "key", "rest of line as value"] — preserves spaces in values
    String[] parts = line.split("\\s+", 3);
    String cmd = parts[0].toUpperCase();

    try {
      return switch (cmd) {
        case "PUT" -> {
          if (parts.length < 3) {
            yield "ERROR PUT requires key and value";
          }
          store.put(parts[1].getBytes(UTF_8), parts[2].getBytes(UTF_8));
          yield "OK";
        }
        case "GET" -> {
          if (parts.length < 2) {
            yield "ERROR GET requires key";
          }
          byte[] val = store.get(parts[1].getBytes(UTF_8));
          yield val != null ? new String(val, UTF_8) : "NOT_FOUND";
        }
        case "DELETE" -> {
          if (parts.length < 2) {
            yield "ERROR DELETE requires key";
          }
          store.delete(parts[1].getBytes(UTF_8));
          yield "OK";
        }
        default -> "ERROR unknown command: " + cmd;
      };
    } catch (IOException e) {
      return "ERROR " + e.getMessage();
    }
  }
}
