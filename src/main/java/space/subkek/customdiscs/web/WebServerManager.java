package space.subkek.customdiscs.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.service.LocalDiscService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class WebServerManager {
  private final CustomDiscs plugin;
  private final Gson gson = new Gson();

  private volatile HttpServer server;
  private volatile ThreadPoolExecutor executor;
  private volatile Semaphore uploadSlots;

  public WebServerManager(CustomDiscs plugin) {
    this.plugin = plugin;
  }

  public synchronized void reload() {
    stop();

    if (!plugin.getCDConfig().isWebEnabled()) {
      return;
    }

    try {
      plugin.getLocalTrackStorage().ensureDirectories();
      ensureUiOverrideExamples();

      int maxConcurrentUploads = Math.max(plugin.getCDConfig().getWebMaxConcurrentUploads(), 1);
      int backlog = Math.max(plugin.getCDConfig().getWebBacklog(), 1);
      int poolSize = Math.max(2, maxConcurrentUploads + 1);

      uploadSlots = new Semaphore(maxConcurrentUploads);
      executor = new ThreadPoolExecutor(
        poolSize,
        poolSize,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(8, poolSize * 4)),
        new NamedThreadFactory("CustomDiscs-Web")
      );
      executor.allowCoreThreadTimeOut(true);

      server = HttpServer.create(new InetSocketAddress(
        plugin.getCDConfig().getWebBindAddress(),
        plugin.getCDConfig().getWebPort()
      ), backlog);
      server.createContext("/api/upload", this::handleUpload);
      server.createContext("/assets/", this::handleAsset);
      server.createContext("/", this::handleIndex);
      server.setExecutor(executor);
      server.start();

      CustomDiscs.info("Built-in upload server listening on {}:{}",
        plugin.getCDConfig().getWebBindAddress(),
        plugin.getCDConfig().getWebPort());
    } catch (Throwable e) {
      stop();
      CustomDiscs.error("Failed to start built-in upload server: ", e);
    }
  }

  public synchronized void stop() {
    plugin.getUploadTokenService().invalidateAll();

    if (server != null) {
      server.stop(0);
      server = null;
    }

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

    uploadSlots = null;
  }

  public String buildUploadUrl(String token, String playerName, Instant expiresAt) {
    String base = plugin.getCDConfig().getWebPublicUrl().trim();
    if (base.isEmpty()) {
      base = "http://127.0.0.1:8080/";
    }
    if (!base.endsWith("/")) {
      base += "/";
    }

    StringBuilder url = new StringBuilder(base)
      .append("?token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));

    if (playerName != null && !playerName.isBlank()) {
      url.append("&player=").append(URLEncoder.encode(playerName, StandardCharsets.UTF_8));
    }

    if (expiresAt != null) {
      url.append("&expire=").append(expiresAt.toEpochMilli());
    }

    return url.toString();
  }

  private void handleIndex(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendJson(exchange, 405, "method_not_allowed", "Solo se permite GET.");
        return;
      }

      if (!"/".equals(exchange.getRequestURI().getPath())) {
        sendJson(exchange, 404, "not_found", "Recurso no encontrado.");
        return;
      }

      serveAsset(exchange, "webui/index.html", getUiOverrideRoot().resolve("index.html"), "text/html; charset=utf-8");
    } finally {
      exchange.close();
    }
  }

  private void handleAsset(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendJson(exchange, 405, "method_not_allowed", "Solo se permite GET.");
        return;
      }

      String requested = exchange.getRequestURI().getPath().substring("/assets/".length());
      if (requested.isBlank() || requested.contains("..") || requested.startsWith("/")) {
        sendJson(exchange, 404, "not_found", "Recurso no encontrado.");
        return;
      }

      Path overrideRoot = getUiOverrideRoot();
      Path overridePath;
      String resourcePath;

      if ("styles.css".equals(requested)) {
        overridePath = overrideRoot.resolve("styles.css");
        resourcePath = "webui/styles.css";
      } else if ("app.js".equals(requested)) {
        overridePath = overrideRoot.resolve("app.js");
        resourcePath = "webui/app.js";
      } else {
        overridePath = overrideRoot.resolve("assets").resolve(requested).normalize();
        if (!overridePath.startsWith(overrideRoot.resolve("assets"))) {
          sendJson(exchange, 404, "not_found", "Resource not found.");
          return;
        }
        resourcePath = "webui/assets/%s".formatted(requested);
      }

      serveAsset(exchange, resourcePath, overridePath, getContentType(requested));
    } finally {
      exchange.close();
    }
  }

  private void handleUpload(HttpExchange exchange) throws IOException {
    Semaphore slots = uploadSlots;
    if (slots == null) {
      sendJson(exchange, 503, "web_disabled", "El servidor de subida no esta disponible.");
      exchange.close();
      return;
    }

    boolean acquired = slots.tryAcquire();
    Path tempPath = null;
    Path finalPath = null;

    try {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendJson(exchange, 405, "method_not_allowed", "Solo se permite POST.");
        return;
      }

      if (!acquired) {
        sendJson(exchange, 429, "too_many_uploads", "Hay demasiadas subidas en curso ahora mismo.");
        return;
      }

      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String token = firstNonBlank(exchange.getRequestHeaders().getFirst("X-CustomDiscs-Token"), query.get("token"));
      String songName = firstNonBlank(exchange.getRequestHeaders().getFirst("X-CustomDiscs-Song-Name"), query.get("songName"));
      String originalFilename = firstNonBlank(exchange.getRequestHeaders().getFirst("X-CustomDiscs-Filename"), query.get("filename"));

      if (songName == null || songName.isBlank()) {
        sendJson(exchange, 400, "missing_song_name", "El nombre de la cancion es obligatorio.");
        return;
      }

      if (originalFilename == null || originalFilename.isBlank()) {
        sendJson(exchange, 400, "missing_filename", "El nombre original del archivo es obligatorio.");
        return;
      }

      if (!plugin.getLocalTrackStorage().isMp3Filename(originalFilename)) {
        sendJson(exchange, 400, "invalid_extension", "Solo se permiten subidas de archivos .mp3.");
        return;
      }

      long maxUploadBytes = Math.max(plugin.getCDConfig().getWebMaxUploadSizeMb(), 1) * 1048576L;
      long contentLength = parseContentLength(exchange.getRequestHeaders().getFirst("Content-Length"));
      if (contentLength > maxUploadBytes) {
        sendJson(exchange, 413, "file_too_large", "El archivo subido supera el limite de tamano configurado.");
        return;
      }

      UploadTokenService.ValidationResult validation = plugin.getUploadTokenService().consume(token);
      if (!validation.success()) {
        sendJson(exchange, validation.failure() == UploadTokenService.Failure.EXPIRED_TOKEN ? 401 : 400,
          validation.failure().getApiCode(),
          messageFor(validation.failure()));
        return;
      }

      Player player = plugin.getServer().getPlayer(validation.playerId());
      if (player == null || !player.isOnline()) {
        sendJson(exchange, 409, "player_offline", "El propietario del token debe estar conectado para completar la subida.");
        return;
      }

      String relativePath = createUploadRelativePath(validation.playerId(), originalFilename, songName);
      finalPath = plugin.getLocalTrackStorage().resolveRelativePath(relativePath);
      tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");

      if (finalPath.getParent() != null) {
        Files.createDirectories(finalPath.getParent());
      }

      saveUpload(exchange.getRequestBody(), tempPath, maxUploadBytes);
      moveIntoPlace(tempPath, finalPath);
      tempPath = null;

      applyUploadToPlayer(player, relativePath, songName);

      sendJson(exchange, 200, Map.of(
        "success", true,
        "code", "ok",
        "message", "La subida se completo y el disco de tu mano principal fue actualizado.",
        "track", relativePath,
        "songName", songName,
        "timestamp", Instant.now().toString()
      ));
    } catch (UploadHttpException e) {
      if (finalPath != null) {
        Files.deleteIfExists(finalPath);
      }
      if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
        sendJson(exchange, e.statusCode(), e.code(), e.getMessage());
      }
    } catch (LocalDiscService.LocalDiscException e) {
      if (finalPath != null) {
        Files.deleteIfExists(finalPath);
      }
      sendJson(exchange, statusFor(e.getFailure()), e.getFailure().getApiCode(), messageFor(e.getFailure()));
    } catch (Throwable e) {
      if (finalPath != null) {
        Files.deleteIfExists(finalPath);
      }
      CustomDiscs.error("Unexpected error while handling web upload: ", e);
      sendJson(exchange, 500, "internal_error", "Error inesperado del servidor.");
    } finally {
      if (tempPath != null) {
        Files.deleteIfExists(tempPath);
      }
      if (acquired) {
        slots.release();
      }
      exchange.close();
    }
  }

  private void applyUploadToPlayer(Player player, String relativePath, String songName) throws Exception {
    CompletableFuture<Void> future = new CompletableFuture<>();

    plugin.getFoliaLib().getScheduler().runAtEntityWithFallback(player, task -> {
      try {
        plugin.getLocalDiscService().applyWebUploadToHeldDisc(player, relativePath, songName);
        future.complete(null);
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    }, () -> future.completeExceptionally(new UploadHttpException(409, "player_offline",
      "El propietario del token debe estar conectado para completar la subida.")));

    try {
      future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      throw new RuntimeException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UploadHttpException(500, "interrupted", "El procesamiento de la subida fue interrumpido.");
    } catch (TimeoutException e) {
      throw new UploadHttpException(504, "apply_timeout", "Se agoto el tiempo al aplicar la cancion subida al jugador.");
    }
  }

  private void saveUpload(InputStream inputStream, Path outputPath, long maxUploadBytes) throws IOException {
    long written = 0L;
    byte[] firstBytes = new byte[10];
    int firstBytesLength = 0;

    try (InputStream input = inputStream;
         OutputStream output = Files.newOutputStream(outputPath)) {
      byte[] buffer = new byte[8192];
      int read;

      while ((read = input.read(buffer)) != -1) {
        if (read == 0) {
          continue;
        }

        written += read;
        if (written > maxUploadBytes) {
          throw new UploadHttpException(413, "file_too_large", "El archivo subido supera el limite de tamano configurado.");
        }

        if (firstBytesLength < firstBytes.length) {
          int copy = Math.min(read, firstBytes.length - firstBytesLength);
          System.arraycopy(buffer, 0, firstBytes, firstBytesLength, copy);
          firstBytesLength += copy;
        }

        output.write(buffer, 0, read);
      }
    }

    if (written == 0) {
      throw new UploadHttpException(400, "empty_upload", "El cuerpo de la subida esta vacio.");
    }

    if (!looksLikeMp3(firstBytes, firstBytesLength)) {
      throw new UploadHttpException(400, "invalid_mp3", "El archivo subido no fue reconocido como audio MP3.");
    }
  }

  private boolean looksLikeMp3(byte[] firstBytes, int length) {
    if (length >= 3 &&
      firstBytes[0] == 'I' &&
      firstBytes[1] == 'D' &&
      firstBytes[2] == '3') {
      return true;
    }

    return length >= 2 &&
      (firstBytes[0] & 0xFF) == 0xFF &&
      (firstBytes[1] & 0xE0) == 0xE0;
  }

  private void moveIntoPlace(Path tempPath, Path finalPath) throws IOException {
    try {
      Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private String createUploadRelativePath(java.util.UUID playerId, String originalFilename, String songName) {
    String baseName = stripExtension(originalFilename);
    String slug = slugify(songName);
    if (slug.isBlank()) {
      slug = slugify(baseName);
    }
    if (slug.isBlank()) {
      slug = "track";
    }

    return plugin.getLocalTrackStorage().normalizeRelativePath("%s/%s-%d-%s.mp3".formatted(
      plugin.getCDConfig().getWebUploadSubdirectory(),
      playerId,
      System.currentTimeMillis(),
      slug
    ));
  }

  private String stripExtension(String filename) {
    int index = filename.lastIndexOf('.');
    if (index <= 0) {
      return filename;
    }
    return filename.substring(0, index);
  }

  private String slugify(String value) {
    String slug = value == null ? "" : value
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("(^-+|-+$)", "");
    return slug;
  }

  private void serveAsset(HttpExchange exchange, String resourcePath, Path overridePath, String contentType) throws IOException {
    byte[] data;
    if (Files.isRegularFile(overridePath)) {
      data = Files.readAllBytes(overridePath);
    } else {
      try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
        if (input == null) {
          sendJson(exchange, 404, "not_found", "Recurso no encontrado.");
          return;
        }
        data = input.readAllBytes();
      }
    }

    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, data.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(data);
    }
  }

  private Path getUiOverrideRoot() {
    return plugin.getDataFolder().toPath()
      .resolve(plugin.getCDConfig().getWebUiOverrideDirectory())
      .normalize();
  }

  private void ensureUiOverrideExamples() throws IOException {
    Path root = getUiOverrideRoot();
    Files.createDirectories(root);
    Files.createDirectories(root.resolve("assets"));

    saveExampleIfMissing(root.resolve("index.html.example"), "webui/index.html");
    saveExampleIfMissing(root.resolve("styles.css.example"), "webui/styles.css");
    saveExampleIfMissing(root.resolve("app.js.example"), "webui/app.js");
  }

  private void saveExampleIfMissing(Path outputPath, String resourcePath) throws IOException {
    if (Files.exists(outputPath)) {
      return;
    }

    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (input == null) {
        return;
      }
      Files.copy(input, outputPath);
    }
  }

  private void sendJson(HttpExchange exchange, int statusCode, String code, String message) throws IOException {
    sendJson(exchange, statusCode, Map.of(
      "success", false,
      "code", code,
      "message", message,
      "timestamp", Instant.now().toString()
    ));
  }

  private void sendJson(HttpExchange exchange, int statusCode, Map<String, ?> payload) throws IOException {
    byte[] data = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(statusCode, data.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(data);
    }
  }

  private long parseContentLength(String rawLength) {
    if (rawLength == null || rawLength.isBlank()) {
      return -1L;
    }

    try {
      return Long.parseLong(rawLength);
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  private Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> result = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return result;
    }

    for (String pair : rawQuery.split("&")) {
      if (pair.isBlank()) {
        continue;
      }

      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      result.put(key, value);
    }

    return result;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private int statusFor(LocalDiscService.Failure failure) {
    return switch (failure) {
      case NOT_HOLDING_DISC, CREATION_LIMIT_REACHED -> 409;
      case FILE_NOT_FOUND -> 404;
      case INVALID_FILE_NAME, DISC_NAME_EMPTY, UNKNOWN_EXTENSION -> 400;
    };
  }

  private String messageFor(LocalDiscService.Failure failure) {
    return switch (failure) {
      case NOT_HOLDING_DISC -> "Debes tener un disco de musica en tu mano principal.";
      case INVALID_FILE_NAME -> "La ruta local de la cancion no es valida.";
      case DISC_NAME_EMPTY -> "El nombre de la cancion es obligatorio.";
      case UNKNOWN_EXTENSION -> "Solo se pueden usar archivos de audio locales compatibles con el plugin.";
      case FILE_NOT_FOUND -> "No se pudo encontrar el archivo de audio subido despues de guardarlo.";
      case CREATION_LIMIT_REACHED -> plugin.getLanguage().string("command.web.messages.error.limit-reached");
    };
  }

  private String messageFor(UploadTokenService.Failure failure) {
    return switch (failure) {
      case MISSING_TOKEN -> "El token de subida es obligatorio.";
      case INVALID_TOKEN -> "El token de subida no es valido.";
      case EXPIRED_TOKEN -> "El token de subida ha expirado.";
    };
  }

  private String getContentType(String path) {
    return switch (plugin.getLocalTrackStorage().getFileExtension(path)) {
      case "css" -> "text/css; charset=utf-8";
      case "js" -> "application/javascript; charset=utf-8";
      case "svg" -> "image/svg+xml";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "webp" -> "image/webp";
      default -> "application/octet-stream";
    };
  }

  private static class UploadHttpException extends IOException {
    private final int statusCode;
    private final String code;

    private UploadHttpException(int statusCode, String code, String message) {
      super(message);
      this.statusCode = statusCode;
      this.code = code;
    }

    public int statusCode() {
      return statusCode;
    }

    public String code() {
      return code;
    }
  }

  private static class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    private NamedThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "%s-%d".formatted(prefix, counter.incrementAndGet()));
      thread.setDaemon(true);
      return thread;
    }
  }
}
