package net.sourceforge.kolmafia.opencode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.RequestLogger;

public final class OpenCodeRuntimeManager {
  private static final String HOST = "127.0.0.1";
  private static final int PORT = 4096;
  private static final URI BASE_URI = URI.create("http://" + HOST + ":" + PORT);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private static volatile Process managedProcess;
  private static String sessionId;

  private OpenCodeRuntimeManager() {}

  public record Health(boolean healthy, String version, boolean managed) {}

  static record RunResult(String sessionId, String text, String diagnostics) {}

  public static Health health() {
    try {
      HttpRequest request =
          requestBuilder(BASE_URI.resolve("/global/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      HttpResponse<String> response =
          HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        return new Health(false, "", isManagedProcessAlive());
      }

      JSONObject payload = JSON.parseObject(response.body());
      return new Health(
          payload.getBooleanValue("healthy"),
          payload.getString("version") == null ? "" : payload.getString("version"),
          isManagedProcessAlive());
    } catch (Exception e) {
      return new Health(false, "", isManagedProcessAlive());
    }
  }

  public static synchronized Health start() throws IOException, InterruptedException {
    Health current = health();
    if (current.healthy()) {
      return current;
    }

    if (isManagedProcessAlive()) {
      managedProcess.destroyForcibly();
      managedProcess = null;
    }

    ProcessBuilder builder =
        new ProcessBuilder(
            executable(), "serve", "--hostname", HOST, "--port", Integer.toString(PORT));
    builder.directory(KoLConstants.ROOT_LOCATION);
    builder.redirectErrorStream(true);
    managedProcess = builder.start();
    startLogPump(managedProcess);

    for (int i = 0; i < 40; i++) {
      Health started = health();
      if (started.healthy()) {
        return new Health(true, started.version(), true);
      }
      Thread.sleep(250);
    }

    Process failed = managedProcess;
    managedProcess = null;
    if (failed != null && failed.isAlive()) {
      failed.destroyForcibly();
    }
    throw new IOException("OpenCode did not become healthy on " + BASE_URI);
  }

  public static synchronized void stop() throws InterruptedException {
    Process process = managedProcess;
    managedProcess = null;
    sessionId = null;

    if (process == null || !process.isAlive()) {
      return;
    }

    process.destroy();
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
  }

  public static synchronized String newSession() throws IOException {
    requireHealthy();
    sessionId = null;
    return "";
  }

  public static synchronized String sendPrompt(String prompt)
      throws IOException, InterruptedException {
    requireHealthy();

    List<String> command = new ArrayList<>();
    command.add(executable());
    command.add("run");
    command.add("--attach");
    command.add(BASE_URI.toString());
    command.add("--format");
    command.add("json");
    command.add("--dir");
    command.add(KoLConstants.ROOT_LOCATION.getAbsolutePath());

    if (sessionId == null) {
      command.add("--title");
      command.add("Kolmaf-AI KoLmafia");
    } else {
      command.add("--session");
      command.add(sessionId);
    }

    command.add(prompt);

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(KoLConstants.ROOT_LOCATION);
    builder.redirectErrorStream(true);

    Process process = builder.start();
    StringBuilder output = new StringBuilder();

    Thread reader =
        Thread.ofVirtual()
            .name("OpenCodeRunOutput")
            .start(
                () -> {
                  try (BufferedReader lines =
                      new BufferedReader(
                          new InputStreamReader(
                              process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                      synchronized (output) {
                        output.append(line).append(System.lineSeparator());
                      }
                    }
                  } catch (IOException ignored) {
                  }
                });

    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
      reader.join(1000);
      throw new IOException("OpenCode attached run did not complete within 2 minutes.");
    }

    reader.join(1000);

    String raw;
    synchronized (output) {
      raw = output.toString();
    }

    RunResult result = parseRunOutput(raw);
    if (result.sessionId() != null && !result.sessionId().isBlank()) {
      sessionId = result.sessionId();
    }

    if (process.exitValue() != 0) {
      throw new IOException(
          "OpenCode attached run failed (exit "
              + process.exitValue()
              + ")"
              + diagnosticSuffix(result.diagnostics()));
    }

    if (result.text().isBlank()) {
      throw new IOException(
          "OpenCode attached run completed without assistant text"
              + diagnosticSuffix(result.diagnostics()));
    }

    return result.text();
  }

  static RunResult parseRunOutput(String output) {
    String foundSession = null;
    StringBuilder text = new StringBuilder();
    StringBuilder diagnostics = new StringBuilder();

    for (String line : output.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      JSONObject event;
      try {
        event = JSON.parseObject(trimmed);
      } catch (Exception e) {
        appendLine(diagnostics, trimmed);
        continue;
      }

      String eventSession = event.getString("sessionID");
      if (eventSession != null && !eventSession.isBlank()) {
        foundSession = eventSession;
      }

      String type = event.getString("type");
      if ("text".equals(type)) {
        JSONObject part = event.getJSONObject("part");
        if (part != null) {
          appendLine(text, part.getString("text"));
        }
      } else if ("error".equals(type)) {
        Object error = event.get("error");
        appendLine(diagnostics, error == null ? trimmed : error.toString());
      }
    }

    return new RunResult(foundSession, text.toString(), diagnostics.toString());
  }

  private static void appendLine(StringBuilder target, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (!target.isEmpty()) {
      target.append(System.lineSeparator());
    }
    target.append(value.trim());
  }

  private static String diagnosticSuffix(String diagnostics) {
    return diagnostics == null || diagnostics.isBlank() ? "." : ": " + diagnostics.trim();
  }

  private static void requireHealthy() throws IOException {
    if (!health().healthy()) {
      throw new IOException("OpenCode is not running. Start the runtime first.");
    }
  }

  private static HttpRequest.Builder requestBuilder(URI uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
    String password = System.getenv("OPENCODE_SERVER_PASSWORD");
    if (password == null || password.isEmpty()) {
      return builder;
    }

    String username = System.getenv("OPENCODE_SERVER_USERNAME");
    if (username == null || username.isEmpty()) {
      username = "opencode";
    }

    String encoded =
        Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    return builder.header("Authorization", "Basic " + encoded);
  }

  private static boolean isManagedProcessAlive() {
    return managedProcess != null && managedProcess.isAlive();
  }

  private static String executable() {
    String configured = System.getenv("KOLMAF_OPENCODE_BIN");
    return configured == null || configured.isBlank() ? "opencode" : configured;
  }

  private static void startLogPump(Process process) {
    Thread pump =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  RequestLogger.printLine("[OpenCode] " + line);
                }
              } catch (IOException e) {
                if (process.isAlive()) {
                  RequestLogger.printLine("[OpenCode] output reader stopped: " + e.getMessage());
                }
              }
            },
            "OpenCodeOutput");
    pump.setDaemon(true);
    pump.start();
  }
}
