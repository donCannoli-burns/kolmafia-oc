package net.sourceforge.kolmafia.opencode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sourceforge.kolmafia.KoLConstants;

public final class OpenCodeRuntimeManager {
  private static String sessionId;

  private OpenCodeRuntimeManager() {}

  public record Health(boolean healthy, String version, boolean managed) {}

  static record RunResult(String sessionId, String text, String diagnostics) {}

  public static Health health() {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(executable(), "--version");
      builder.directory(KoLConstants.ROOT_LOCATION);
      builder.redirectErrorStream(true);
      process = builder.start();

      String output;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
      }

      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Health(false, "", false);
      }

      if (process.exitValue() != 0) {
        return new Health(false, "", false);
      }

      return new Health(true, output == null ? "" : output.trim(), false);
    } catch (Exception e) {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      return new Health(false, "", false);
    }
  }

  public static synchronized Health start() throws IOException {
    Health current = health();
    if (!current.healthy()) {
      throw new IOException(
          "OpenCode CLI was not available. Set KOLMAF_OPENCODE_BIN or put opencode on PATH.");
    }
    return current;
  }

  public static synchronized void stop() {
    sessionId = null;
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
    command.add("--format");
    command.add("json");

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
      throw new IOException("OpenCode run did not complete within 2 minutes.");
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
          "OpenCode run failed (exit "
              + process.exitValue()
              + ")"
              + diagnosticSuffix(result.diagnostics()));
    }

    if (result.text().isBlank()) {
      throw new IOException(
          "OpenCode run completed without assistant text" + diagnosticSuffix(result.diagnostics()));
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
      throw new IOException("OpenCode CLI is not available.");
    }
  }

  private static String executable() {
    String configured = System.getenv("KOLMAF_OPENCODE_BIN");
    return configured == null || configured.isBlank() ? "opencode" : configured;
  }
}
