package space.subkek.customdiscs.storage;

import space.subkek.customdiscs.CustomDiscs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class LocalTrackStorage {
  private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp3", "wav", "flac");
  private final CustomDiscs plugin;

  public LocalTrackStorage(CustomDiscs plugin) {
    this.plugin = plugin;
  }

  public void ensureDirectories() throws IOException {
    Files.createDirectories(getRootPath());
    Files.createDirectories(getUploadDirectory());
  }

  public Path getRootPath() {
    return plugin.getDataFolder().toPath()
      .resolve(plugin.getCDConfig().getLocalStorageDirectory())
      .normalize();
  }

  public File getRootDirectory() {
    return getRootPath().toFile();
  }

  public Path getUploadDirectory() {
    return getRootPath()
      .resolve(normalizeRelativePath(plugin.getCDConfig().getWebUploadSubdirectory()))
      .normalize();
  }

  public boolean isSupportedAudioPath(String relativePath) {
    return SUPPORTED_EXTENSIONS.contains(getFileExtension(relativePath));
  }

  public boolean isSupportedAudioFilename(String filename) {
    return isSupportedAudioPath(filename);
  }

  public boolean isMp3Filename(String filename) {
    return "mp3".equals(getFileExtension(filename));
  }

  public String normalizeRelativePath(String rawPath) {
    if (rawPath == null) {
      throw new IllegalArgumentException("Path is null");
    }

    String path = rawPath.trim().replace('\\', '/');
    while (path.startsWith("/")) {
      path = path.substring(1);
    }

    if (path.isBlank()) {
      throw new IllegalArgumentException("Path is empty");
    }

    Path normalized = Path.of(path).normalize();
    if (normalized.isAbsolute()) {
      throw new IllegalArgumentException("Absolute paths are not allowed");
    }

    String result = normalized.toString().replace(File.separatorChar, '/');
    if (result.isBlank() || ".".equals(result) || result.startsWith("..")) {
      throw new IllegalArgumentException("Path traversal is not allowed");
    }

    return result;
  }

  public Path resolveRelativePath(String relativePath) {
    String normalized = normalizeRelativePath(relativePath);
    Path root = getRootPath();
    Path resolved = root.resolve(normalized).normalize();

    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Path traversal is not allowed");
    }

    return resolved;
  }

  public List<String> listTrackFiles() {
    Path root = getRootPath();
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (Stream<Path> stream = Files.walk(root)) {
      return stream
        .filter(Files::isRegularFile)
        .filter(path -> isSupportedAudioPath(root.relativize(path).toString()))
        .map(root::relativize)
        .map(path -> path.toString().replace(File.separatorChar, '/'))
        .sorted(Comparator.naturalOrder())
        .toList();
    } catch (IOException e) {
      CustomDiscs.error("Failed to list local tracks: ", e);
      return List.of();
    }
  }

  public String getFileExtension(String filename) {
    int index = filename.lastIndexOf('.');
    if (index < 0 || index == filename.length() - 1) {
      return "";
    }

    return filename.substring(index + 1).toLowerCase(Locale.ROOT);
  }
}
