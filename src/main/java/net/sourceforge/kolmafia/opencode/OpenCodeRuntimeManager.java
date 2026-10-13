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
import java.util.Base64;
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

  public static synchronized String newSession() throws IOException, InterruptedException {
    requireHealthy();
    sessionId = null;
    return ensureSession();
  }

  public static synchronized String sendPrompt(String prompt)
      throws IOException, InterruptedException {
    requireHealthy();
    String id = ensureSession();

    JSONObject textPart = new JSONObject();
    textPart.put("type", "text");
    textPart.put("text", prompt);

    JSONArray parts = new JSONArray();
    parts.add(textPart);

    JSONObject body = new JSONObject();
    body.put("parts", parts);

    HttpRequest request =
        requestBuilder(BASE_URI.resolve("/session/" + id + "/message"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    body.toJSONString(), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(response, "send OpenCode message");

    String text = extractAssistantText(response.body());
    return text.isBlank() ? "(OpenCode completed without a text response.)" : text;
  }

  static String extractAssistantText(String json) {
    JSONObject payload = JSON.parseObject(json);

    String direct = extractTextParts(payload.getJSONArray("parts"));
    if (!direct.isBlank()) {
      return direct;
    }

    JSONObject dataObject = payload.getJSONObject("data");
    if (dataObject != null) {
      String dataParts = extractTextParts(dataObject.getJSONArray("parts"));
      if (!dataParts.isBlank()) {
        return dataParts;
      }

      String dataContent = extractTextParts(dataObject.getJSONArray("content"));
      if (!dataContent.isBlank()) {
        return dataContent;
      }
    }

    JSONArray data = payload.getJSONArray("data");
    if (data == null) {
      return "";
    }

    for (int i = 0; i < data.size(); i++) {
      JSONObject message = data.getJSONObject(i);
      if (!"assistant".equals(message.getString("type"))) {
        continue;
      }

      String text = extractTextParts(message.getJSONArray("content"));
      if (!text.isBlank()) {
        return text;
      }
    }

    return "";
  }

  private static String extractTextParts(JSONArray parts) {
    if (parts == null) {
      return "";
    }

    StringBuilder result = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      JSONObject part = parts.getJSONObject(i);
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

  private static String ensureSession() throws IOException, InterruptedException {
    if (sessionId != null) {
      return sessionId;
    }

    JSONObject body = new JSONObject();
    body.put("title", "Kolmaf-AI KoLmafia");

    HttpResponse<String> response = sendJson("POST", "/session", body.toJSONString());
    ensureSuccess(response, "create OpenCode session");

    JSONObject payload = JSON.parseObject(response.body());
    Object id = payload.get("id");
    if (id == null) {
      JSONObject data = payload.getJSONObject("data");
      id = data == null ? null : data.get("id");
    }
    if (id == null) {
      throw new IOException("OpenCode session response did not contain a session id.");
    }

    sessionId = id.toString();
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
        requestBuilder(BASE_URI.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
