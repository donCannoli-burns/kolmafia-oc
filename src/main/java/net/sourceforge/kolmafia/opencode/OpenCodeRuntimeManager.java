package net.sourceforge.kolmafia.opencode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OpenCodeRuntimeManager {
  private static final Map<AgentMode, String> sessionIds = new EnumMap<>(AgentMode.class);

  private OpenCodeRuntimeManager() {}

  public enum AgentMode {
    PLAN("Plan", "kolmaf-ai-plan"),
    BUILD("Build", "kolmaf-ai-build");

    private final String displayName;
    private final String cliName;

    AgentMode(String displayName, String cliName) {
      this.displayName = displayName;
      this.cliName = cliName;
    }

    public String cliName() {
      return this.cliName;
    }

    @Override
    public String toString() {
      return this.displayName;
    }
  }

  public record Health(boolean healthy, String version, boolean managed) {}

  static record RunResult(String sessionId, String text, String diagnostics) {}

  public static Health health() {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(executable(), "--version");
      builder.directory(workspace());
      builder.redirectErrorStream(true);
      process = builder.start();

      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
        return new Health(false, "", false);
      }

      String output;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
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
    sessionIds.clear();
  }

  public static synchronized String newSession(AgentMode mode) throws IOException {
    requireHealthy();
    sessionIds.remove(mode);
    return "";
  }

  public static synchronized String sendPrompt(String prompt, AgentMode mode)
      throws IOException, InterruptedException {
    requireHealthy();

    String existingSessionId = sessionIds.get(mode);
    List<String> command = buildRunCommand(prompt, mode, existingSessionId);

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workspace());
    builder.redirectErrorStream(true);

    Process process = builder.start();
    // OpenCode treats non-TTY stdin as an input stream. ProcessBuilder creates an open pipe by
    // default, so explicitly send EOF; otherwise `opencode run` can finish its response but wait
    // forever for more stdin.
    process.getOutputStream().close();

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

      String partial;
      synchronized (output) {
        partial = output.toString();
      }
      RunResult partialResult = parseRunOutput(partial);
      String detail =
          !partialResult.text().isBlank()
              ? " OpenCode had already produced: " + partialResult.text()
              : diagnosticSuffix(partialResult.diagnostics());
      throw new IOException("OpenCode run did not complete within 2 minutes." + detail);
    }

    reader.join(1000);

    String raw;
    synchronized (output) {
      raw = output.toString();
    }

    RunResult result = parseRunOutput(raw);
    if (result.sessionId() != null && !result.sessionId().isBlank()) {
      sessionIds.put(mode, result.sessionId());
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

  static List<String> buildRunCommand(
      String prompt, AgentMode mode, String existingSessionId) {
    List<String> command = new ArrayList<>();
    command.add(executable());
    command.add("run");
    command.add("--format");
    command.add("json");
    command.add("--agent");
    command.add(mode.cliName());
    command.add("--dir");
    command.add(workspace().getAbsolutePath());

    if (existingSessionId == null) {
      command.add("--title");
      command.add("Kolmaf-AI " + mode + " KoLmafia");
    } else {
      command.add("--session");
      command.add(existingSessionId);
    }

    command.add(prompt);
    return command;
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

  private static File workspace() {
    String configured = System.getenv("KOLMAF_OPENCODE_WORKSPACE");
    String path =
        configured == null || configured.isBlank()
            ? System.getProperty("user.dir")
            : configured;
    return new File(path).getAbsoluteFile();
  }

  private static String executable() {
    String configured = System.getenv("KOLMAF_OPENCODE_BIN");
    return configured == null || configured.isBlank() ? "opencode" : configured;
  }
}
