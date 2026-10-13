package net.sourceforge.kolmafia.opencode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
import java.util.concurrent.TimeUnit;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.RequestLogger;

public final class OpenCodeRuntimeManager {
  private static final String HOST = "127.0.0.1";
  private static final int PORT = 4096;
  private static final URI BASE_URI = URI.create("http://" + HOST + ":" + PORT);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private static Process managedProcess;
  private static String sessionId;

  private OpenCodeRuntimeManager() {}

  public record Health(boolean healthy, String version, boolean managed) {}

  public static Health health() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(BASE_URI.resolve("/global/health"))
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

  public static synchronized String newSession() throws IOException, InterruptedException {
    requireHealthy();
    sessionId = null;
    return ensureSession();
  }

  public static synchronized String sendPrompt(String prompt)
      throws IOException, InterruptedException {
    requireHealthy();
    String id = ensureSession();

    JSONObject promptPayload = new JSONObject();
    promptPayload.put("text", prompt);
    JSONObject body = new JSONObject();
    body.put("prompt", promptPayload);

    HttpResponse<String> admitted =
        sendJson("POST", "/api/session/" + id + "/prompt", body.toJSONString());
    ensureSuccess(admitted, "send prompt");

    HttpRequest waitRequest =
        HttpRequest.newBuilder(BASE_URI.resolve("/api/experimental/session/" + id + "/wait"))
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> waited =
        HTTP.send(waitRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(waited, "wait for OpenCode response");

    HttpRequest messagesRequest =
        HttpRequest.newBuilder(
                BASE_URI.resolve(
                    "/api/session/" + id + "/message?type=assistant&order=desc&limit=1"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> messages =
        HTTP.send(messagesRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(messages, "read OpenCode response");

    String text = extractAssistantText(messages.body());
    return text.isBlank() ? "(OpenCode completed without a text response.)" : text;
  }

  static String extractAssistantText(String json) {
    JSONObject payload = JSON.parseObject(json);
    JSONArray data = payload.getJSONArray("data");
    if (data == null) {
      return "";
    }

    for (int i = 0; i < data.size(); i++) {
      JSONObject message = data.getJSONObject(i);
      if (!"assistant".equals(message.getString("type"))) {
        continue;
      }

      JSONArray content = message.getJSONArray("content");
      if (content == null) {
        return "";
      }

      StringBuilder result = new StringBuilder();
      for (int j = 0; j < content.size(); j++) {
        JSONObject part = content.getJSONObject(j);
        if (!"text".equals(part.getString("type"))) {
          continue;
        }

        String text = part.getString("text");
        if (text == null || text.isBlank()) {
          continue;
        }
        if (!result.isEmpty()) {
          result.append(System.lineSeparator());
        }
        result.append(text);
      }
      return result.toString();
    }

    return "";
  }

  private static String ensureSession() throws IOException, InterruptedException {
    if (sessionId != null) {
      return sessionId;
    }

    JSONObject location = new JSONObject();
    location.put("directory", KoLConstants.ROOT_LOCATION.getAbsolutePath());

    JSONObject body = new JSONObject();
    body.put("title", "Kolmaf-AI KoLmafia");
    body.put("location", location);

    HttpResponse<String> response = sendJson("POST", "/api/session", body.toJSONString());
    ensureSuccess(response, "create OpenCode session");

    JSONObject payload = JSON.parseObject(response.body());
    JSONObject data = payload.getJSONObject("data");
    if (data == null || data.get("id") == null) {
      throw new IOException("OpenCode session response did not contain a session id.");
    }

    sessionId = data.get("id").toString();
    return sessionId;
  }

  private static void requireHealthy() throws IOException {
    if (!health().healthy()) {
      throw new IOException("OpenCode is not running. Start the runtime first.");
    }
  }

  private static HttpResponse<String> sendJson(String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(BASE_URI.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void ensureSuccess(HttpResponse<String> response, String operation)
      throws IOException {
    if (response.statusCode() / 100 == 2) {
      return;
    }

    String body = response.body() == null ? "" : response.body().trim();
    throw new IOException(
        "OpenCode failed to "
            + operation
            + " (HTTP "
            + response.statusCode()
            + ")"
            + (body.isEmpty() ? "" : ": " + body));
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
