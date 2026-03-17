package space.subkek.customdiscs.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.Keys;
import space.subkek.customdiscs.file.CDData;
import space.subkek.customdiscs.util.DiscLoreFormatter;
import space.subkek.customdiscs.util.LegacyUtil;
import space.subkek.customdiscs.util.TrackDurationUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class LocalDiscService {
  private final CustomDiscs plugin;

  public LocalDiscService(CustomDiscs plugin) {
    this.plugin = plugin;
  }

  public void applyToHeldDisc(Player player, String relativeTrackPath, String customName) throws LocalDiscException {
    PreparedTrack preparedTrack = prepareTrack(relativeTrackPath, customName);
    applyPreparedToHeldDisc(player, preparedTrack, null);
  }

  public void applyWebUploadToHeldDisc(Player player, String relativeTrackPath, String customName) throws LocalDiscException {
    PreparedTrack preparedTrack = prepareTrack(relativeTrackPath, customName);
    if (!LegacyUtil.isMusicDiscInHand(player)) {
      throw new LocalDiscException(Failure.NOT_HOLDING_DISC);
    }

    int limit = resolveWebCreationLimit(player);
    String discId = UUID.randomUUID().toString();
    CDData.WebDiscRecord record = new CDData.WebDiscRecord(
      discId,
      player.getUniqueId(),
      preparedTrack.customName(),
      preparedTrack.normalizedPath(),
      Instant.now().toEpochMilli()
    );

    if (!plugin.getCDData().registerWebDisc(record, limit)) {
      throw new LocalDiscException(Failure.CREATION_LIMIT_REACHED);
    }

    boolean applied = false;
    try {
      applyPreparedToHeldDisc(player, preparedTrack, discId);
      applied = true;
      plugin.getCDData().save();
    } finally {
      if (!applied) {
        if (plugin.getCDData().removeWebDisc(discId) != null) {
          plugin.getCDData().save();
        }
      }
    }
  }

  public int resolveWebCreationLimit(Player player) {
    return plugin.getCDConfig().resolvePlayerCreationLimit(player);
  }

  public int countPlayerWebDiscs(UUID playerId) {
    return plugin.getCDData().countWebDiscsByOwner(playerId);
  }

  public boolean isWebCreationLimitReached(Player player) {
    int limit = resolveWebCreationLimit(player);
    if (limit < 0) {
      return false;
    }
    return countPlayerWebDiscs(player.getUniqueId()) >= limit;
  }

  private PreparedTrack prepareTrack(String relativeTrackPath, String customName) throws LocalDiscException {
    if (customName == null || customName.isBlank()) {
      throw new LocalDiscException(Failure.DISC_NAME_EMPTY);
    }

    final String normalizedPath;
    try {
      normalizedPath = plugin.getLocalTrackStorage().normalizeRelativePath(relativeTrackPath);
    } catch (IllegalArgumentException e) {
      throw new LocalDiscException(Failure.INVALID_FILE_NAME);
    }

    if (!plugin.getLocalTrackStorage().isSupportedAudioPath(normalizedPath)) {
      throw new LocalDiscException(Failure.UNKNOWN_EXTENSION);
    }

    Path trackPath = plugin.getLocalTrackStorage().resolveRelativePath(normalizedPath);
    if (!Files.isRegularFile(trackPath)) {
      throw new LocalDiscException(Failure.FILE_NOT_FOUND);
    }

    return new PreparedTrack(normalizedPath, trackPath, customName);
  }

  @SuppressWarnings("UnstableApiUsage")
  private void applyPreparedToHeldDisc(Player player, PreparedTrack preparedTrack, @Nullable String discId) throws LocalDiscException {
    if (!LegacyUtil.isMusicDiscInHand(player)) {
      throw new LocalDiscException(Failure.NOT_HOLDING_DISC);
    }

    ItemStack heldDisc = player.getInventory().getItemInMainHand();
    ItemMeta meta = LegacyUtil.getItemMeta(heldDisc);

    meta.displayName(plugin.getLanguage().component("disc-name.simple")
      .decoration(TextDecoration.ITALIC, false));
    meta.addItemFlags(ItemFlag.values());
    String songLength = TrackDurationUtil.resolveLocalDurationLabel(preparedTrack.trackPath(), "Unknown");
    List<Component> lore = DiscLoreFormatter.build(
      plugin.getCDConfig(),
      preparedTrack.customName(),
      player.getName(),
      Instant.now(),
      songLength
    );
    meta.lore(lore.isEmpty() ? null : lore);

    PersistentDataContainer data = meta.getPersistentDataContainer();
    for (NamespacedKey key : List.copyOf(data.getKeys())) {
      data.remove(key);
    }
    data.set(Keys.LOCAL_DISC.key(), Keys.LOCAL_DISC.dataType(), preparedTrack.normalizedPath());
    data.set(Keys.SONG_NAME.key(), Keys.SONG_NAME.dataType(), preparedTrack.customName());
    if (discId != null && !discId.isBlank()) {
      data.set(Keys.DISC_ID.key(), Keys.DISC_ID.dataType(), discId);
    }

    int modelData = plugin.getCDConfig().getLocalCustomModelData();
    if (modelData != 0) {
      heldDisc.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
        CustomModelData.customModelData().addFloat(modelData).build());
    }

    heldDisc.setItemMeta(meta);
  }

  @Getter
  @RequiredArgsConstructor
  public enum Failure {
    NOT_HOLDING_DISC("not_holding_disc", "command.create.messages.error.not-holding-disc"),
    INVALID_FILE_NAME("invalid_file_name", "error.command.invalid-filename"),
    DISC_NAME_EMPTY("disc_name_empty", "error.command.disc-name-empty"),
    UNKNOWN_EXTENSION("unknown_extension", "error.command.unknown-extension"),
    FILE_NOT_FOUND("file_not_found", "error.file.not-found"),
    CREATION_LIMIT_REACHED("creation_limit_reached", "command.create.messages.error.limit-reached");

    private final String apiCode;
    private final String languageKey;
  }

  public static class LocalDiscException extends Exception {
    @Getter
    private final Failure failure;

    public LocalDiscException(Failure failure) {
      super(failure.name());
      this.failure = failure;
    }
  }

  private record PreparedTrack(String normalizedPath, Path trackPath, String customName) {
  }
}
