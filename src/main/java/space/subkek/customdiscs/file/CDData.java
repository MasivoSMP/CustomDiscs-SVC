package space.subkek.customdiscs.file;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.util.LegacyUtil;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Getter
@RequiredArgsConstructor
public class CDData {
  private final YamlFile yaml = new YamlFile();
  private final File dataFile;

  private WrappedTask autosaveTask;

  private final HashMap<UUID, Integer> jukeboxDistanceMap = new HashMap<>();
  private final HashMap<String, WebDiscRecord> webDiscMap = new HashMap<>();

  public synchronized void load() {
    jukeboxDistanceMap.clear();
    webDiscMap.clear();

    if (dataFile.exists()) {
      try {
        yaml.load(dataFile);
      } catch (IOException e) {
        CustomDiscs.error("Error while loading config: ", e);
      }
    }

    loadJukeboxDistances();
    loadWebDiscs();
  }

  public synchronized void save() {
    yaml.set("jukebox.distance", null);
    jukeboxDistanceMap.forEach((uuid, distance) ->
      yaml.set("jukebox.distance.%s".formatted(uuid), distance));

    yaml.set("discs.web", null);
    webDiscMap.values().forEach(record -> {
      String root = "discs.web.%s".formatted(record.id());
      yaml.set("%s.id".formatted(root), record.id());
      yaml.set("%s.owner".formatted(root), record.owner().toString());
      yaml.set("%s.songName".formatted(root), record.songName());
      yaml.set("%s.relativePath".formatted(root), record.relativePath());
      yaml.set("%s.createdAt".formatted(root), record.createdAt());
    });

    try {
      yaml.save(dataFile);
    } catch (IOException e) {
      CustomDiscs.error("Error saving data: ", e);
    }
  }

  public void startAutosave() {
    if (autosaveTask != null) throw new IllegalStateException("Autosave data task already exists");
    autosaveTask = CustomDiscs.getPlugin().getFoliaLib().getScheduler().runTimerAsync(
      this::save,
      60, 60,
      TimeUnit.SECONDS
    );
  }

  public void stopAutosave() {
    if (autosaveTask != null) {
      autosaveTask.cancel();
      autosaveTask = null;
    }
  }

  public synchronized int getJukeboxDistance(Block block) {
    UUID blockUUID = LegacyUtil.getBlockUUID(block);
    return jukeboxDistanceMap.containsKey(blockUUID) ?
      jukeboxDistanceMap.get(blockUUID) : CustomDiscs.getPlugin().getCDConfig().getMusicDiscDistance();
  }

  public synchronized int countWebDiscsByOwner(UUID owner) {
    return (int) webDiscMap.values().stream()
      .filter(record -> record.owner().equals(owner))
      .count();
  }

  public synchronized List<WebDiscRecord> listWebDiscsByOwner(UUID owner) {
    return webDiscMap.values().stream()
      .filter(record -> record.owner().equals(owner))
      .sorted(Comparator.comparingLong(WebDiscRecord::createdAt)
        .reversed()
        .thenComparing(WebDiscRecord::id))
      .toList();
  }

  public synchronized boolean registerWebDisc(WebDiscRecord record, int limit) {
    if (limit >= 0 && countWebDiscsByOwner(record.owner()) >= limit) {
      return false;
    }
    webDiscMap.put(record.id(), record);
    return true;
  }

  public synchronized boolean webDiscExists(String id) {
    return webDiscMap.containsKey(id);
  }

  @Nullable
  public synchronized WebDiscRecord getWebDisc(String id) {
    return webDiscMap.get(id);
  }

  @Nullable
  public synchronized WebDiscRecord removeWebDisc(String id) {
    return webDiscMap.remove(id);
  }

  private void loadJukeboxDistances() {
    ConfigurationSection section = yaml.getConfigurationSection("jukebox.distance");
    if (section == null) return;

    for (String key : section.getKeys(false)) {
      UUID uuid;
      try {
        uuid = UUID.fromString(key);
      } catch (IllegalArgumentException e) {
        CustomDiscs.warn("Skipping invalid jukebox distance key '{}'", key);
        continue;
      }
      int distance = section.getInt(key);

      jukeboxDistanceMap.put(uuid, distance);
    }
  }

  private void loadWebDiscs() {
    ConfigurationSection section = yaml.getConfigurationSection("discs.web");
    if (section == null) return;

    for (String key : section.getKeys(false)) {
      UUID discUUID;
      try {
        discUUID = UUID.fromString(key);
      } catch (IllegalArgumentException e) {
        CustomDiscs.warn("Skipping invalid tracked disc id '{}'", key);
        continue;
      }

      ConfigurationSection discSection = section.getConfigurationSection(key);
      if (discSection == null) {
        continue;
      }

      String ownerRaw = discSection.getString("owner");
      UUID owner;
      try {
        owner = UUID.fromString(ownerRaw);
      } catch (Throwable e) {
        CustomDiscs.warn("Skipping tracked disc '{}' with invalid owner '{}'", key, ownerRaw);
        continue;
      }

      String songName = discSection.getString("songName", "Unknown");
      String relativePath = discSection.getString("relativePath");
      if (relativePath == null || relativePath.isBlank()) {
        CustomDiscs.warn("Skipping tracked disc '{}' because relativePath is missing", key);
        continue;
      }

      long createdAt = parseCreatedAt(discSection.get("createdAt"));
      String id = discUUID.toString();
      webDiscMap.put(id, new WebDiscRecord(id, owner, songName, relativePath, createdAt));
    }
  }

  private long parseCreatedAt(Object rawValue) {
    if (rawValue instanceof Number number) {
      return number.longValue();
    }
    if (rawValue != null) {
      try {
        return Long.parseLong(String.valueOf(rawValue));
      } catch (NumberFormatException ignored) {
      }
    }
    return System.currentTimeMillis();
  }

  public record WebDiscRecord(String id, UUID owner, String songName, String relativePath, long createdAt) {
  }
}
