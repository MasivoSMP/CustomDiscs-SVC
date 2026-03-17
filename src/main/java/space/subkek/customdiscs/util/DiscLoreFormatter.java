package space.subkek.customdiscs.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import space.subkek.customdiscs.file.CDConfig;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DiscLoreFormatter {
  private static final MiniMessage MINIMESSAGE = MiniMessage.miniMessage();
  private static final String FALLBACK_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

  private DiscLoreFormatter() {
  }

  public static List<Component> build(CDConfig config, String songName, String creator, Instant createdAt, String songLength) {
    List<String> lines = config.getDiscLoreLines();
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }

    String safeSongName = MINIMESSAGE.escapeTags(songName == null ? "" : songName);
    String safeCreator = MINIMESSAGE.escapeTags(creator == null ? "" : creator);
    String safeDate = MINIMESSAGE.escapeTags(formatDate(createdAt, config.getDiscLoreDateFormat()));
    String safeSongLength = MINIMESSAGE.escapeTags(songLength == null ? "" : songLength);

    List<Component> lore = new ArrayList<>(lines.size());
    for (String line : lines) {
      if (line == null) {
        continue;
      }

      String resolved = line
        .replace("{song-name}", safeSongName)
        .replace("{song_name}", safeSongName)
        .replace("{disc-creator}", safeCreator)
        .replace("{disc_creator}", safeCreator)
        .replace("{created-date}", safeDate)
        .replace("{created_date}", safeDate)
        .replace("{song-length}", safeSongLength)
        .replace("{song_length}", safeSongLength);

      lore.add(MINIMESSAGE.deserialize(resolved)
        .decoration(TextDecoration.ITALIC, false));
    }

    return lore;
  }

  private static String formatDate(Instant createdAt, String pattern) {
    String resolvedPattern = (pattern == null || pattern.isBlank()) ? FALLBACK_DATE_FORMAT : pattern;
    DateTimeFormatter formatter;
    try {
      formatter = DateTimeFormatter.ofPattern(resolvedPattern, Locale.ENGLISH);
    } catch (IllegalArgumentException ignored) {
      formatter = DateTimeFormatter.ofPattern(FALLBACK_DATE_FORMAT, Locale.ENGLISH);
    }
    return formatter.withZone(ZoneId.systemDefault()).format(createdAt);
  }
}
