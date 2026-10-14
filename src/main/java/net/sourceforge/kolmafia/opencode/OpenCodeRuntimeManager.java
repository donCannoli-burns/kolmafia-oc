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

    int assistantCountBefore = readAssistantMessages(id).size();

    JSONObject textPart = new JSONObject();
    textPart.put("type", "text");
    textPart.put("text", prompt);

    JSONArray parts = new JSONArray();
    parts.add(textPart);

    JSONObject body = new JSONObject();
    body.put("parts", parts);

    HttpResponse<String> admitted =
        sendJson("POST", "/session/" + id + "/prompt_async", body.toJSONString());
    ensureSuccess(admitted, "admit OpenCode prompt");

    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    String lastStatus = "unknown";

    while (System.nanoTime() < deadline) {
      List<String> assistants = readAssistantMessages(id);
      if (assistants.size() > assistantCountBefore) {
        String text = assistants.get(assistants.size() - 1);
        return text.isBlank() ? "(OpenCode completed without a text response.)" : text;
      }

      lastStatus = readSessionStatus(id);
      if (lastStatus.startsWith("retry:")) {
        abortSession(id);
        throw new IOException("OpenCode provider is retrying: " + lastStatus.substring(6).trim());
      }

      String pending = readPendingInteraction(id);
      if (!pending.isBlank()) {
        abortSession(id);
        throw new IOException(
            "OpenCode is waiting for " + pending + ". Kolmaf-AI does not auto-approve requests.");
      }

      Thread.sleep(500);
    }

    abortSession(id);
    throw new IOException(
        "OpenCode did not produce an assistant response within 90 seconds"
            + " (last session status: "
            + lastStatus
            + "). The session was aborted.");
  }

  static String extractAssistantText(String json) {
    List<String> messages = extractAssistantMessages(json);
    return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
  }

  private static List<String> readAssistantMessages(String id)
      throws IOException, InterruptedException {
    HttpRequest request =
        requestBuilder(BASE_URI.resolve("/session/" + id + "/message?limit=50"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(response, "read OpenCode messages");
    return extractAssistantMessages(response.body());
  }

  static List<String> extractAssistantMessages(String json) {
    Object parsed = JSON.parse(json);
    JSONArray messages;

    if (parsed instanceof JSONArray array) {
      messages = array;
    } else if (parsed instanceof JSONObject object) {
      JSONArray data = object.getJSONArray("data");
      if (data != null) {
        messages = data;
      } else {
        String direct = extractTextParts(object.getJSONArray("parts"));
        return direct.isBlank() ? List.of() : List.of(direct);
      }
    } else {
      return List.of();
    }

    List<String> result = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      JSONObject message = messages.getJSONObject(i);
      JSONObject info = message.getJSONObject("info");

      String role = info == null ? message.getString("type") : info.getString("role");
      if (!"assistant".equals(role)) {
        continue;
      }

      JSONArray parts = message.getJSONArray("parts");
      if (parts == null) {
        parts = message.getJSONArray("content");
      }
      String text = extractTextParts(parts);
      if (!text.isBlank()) {
        result.add(text);
      }
    }
    return result;
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

  private static String readSessionStatus(String id) throws IOException, InterruptedException {
    HttpRequest request =
        requestBuilder(BASE_URI.resolve("/session/status"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(response, "read OpenCode session status");

    JSONObject statusMap = JSON.parseObject(response.body());
    JSONObject status = statusMap.getJSONObject(id);
    if (status == null) {
      return "idle";
    }

    String type = status.getString("type");
    if ("retry".equals(type)) {
      String message = status.getString("message");
      return "retry: " + (message == null ? "provider retry scheduled" : message);
    }
    return type == null ? "unknown" : type;
  }

  private static String readPendingInteraction(String id)
      throws IOException, InterruptedException {
    if (hasPendingForSession("/permission", id)) {
      return "a permission decision";
    }
    if (hasPendingForSession("/question", id)) {
      return "user input";
    }
    return "";
  }

  private static boolean hasPendingForSession(String path, String id)
      throws IOException, InterruptedException {
    HttpRequest request =
        requestBuilder(BASE_URI.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    ensureSuccess(response, "read OpenCode pending requests");

    Object parsed = JSON.parse(response.body());
    JSONArray requests = null;
    if (parsed instanceof JSONArray array) {
      requests = array;
    } else if (parsed instanceof JSONObject object) {
      requests = object.getJSONArray("data");
    }
    if (requests == null) {
      return false;
    }

    for (int i = 0; i < requests.size(); i++) {
      JSONObject pending = requests.getJSONObject(i);
      if (id.equals(pending.getString("sessionID"))) {
        return true;
      }
    }
    return false;
  }

  private static void abortSession(String id) {
    try {
      HttpRequest request =
          requestBuilder(BASE_URI.resolve("/session/" + id + "/abort"))
              .timeout(Duration.ofSeconds(5))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HTTP.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception ignored) {
    }
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
