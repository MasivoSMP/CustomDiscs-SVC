package space.subkek.customdiscs.util;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TrackDurationUtil {
  private static final AudioPlayerManager METADATA_MANAGER = new DefaultAudioPlayerManager();
  private static final long METADATA_TIMEOUT_SECONDS = 8L;

  static {
    METADATA_MANAGER.registerSourceManager(new LocalAudioSourceManager());
  }

  private TrackDurationUtil() {
  }

  public static String resolveLocalDurationLabel(Path filePath, String fallback) {
    OptionalLong durationMs = resolveLocalDurationMillis(filePath);
    if (durationMs.isEmpty()) {
      return fallback;
    }
    return formatDuration(durationMs.getAsLong());
  }

  public static OptionalLong resolveLocalDurationMillis(Path filePath) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicLong durationMs = new AtomicLong(-1L);

    METADATA_MANAGER.loadItem(filePath.toString(), new AudioLoadResultHandler() {
      @Override
      public void trackLoaded(AudioTrack audioTrack) {
        durationMs.set(audioTrack.getDuration());
        latch.countDown();
      }

      @Override
      public void playlistLoaded(AudioPlaylist audioPlaylist) {
        AudioTrack selected = audioPlaylist.getSelectedTrack();
        if (selected == null && !audioPlaylist.getTracks().isEmpty()) {
          selected = audioPlaylist.getTracks().getFirst();
        }
        if (selected != null) {
          durationMs.set(selected.getDuration());
        }
        latch.countDown();
      }

      @Override
      public void noMatches() {
        latch.countDown();
      }

      @Override
      public void loadFailed(FriendlyException e) {
        latch.countDown();
      }
    });

    try {
      if (!latch.await(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return OptionalLong.empty();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OptionalLong.empty();
    }

    long value = durationMs.get();
    return value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
  }

  public static String formatDuration(long millis) {
    long totalSeconds = Math.max(0L, millis / 1000L);
    long hours = totalSeconds / 3600L;
    long minutes = (totalSeconds % 3600L) / 60L;
    long seconds = totalSeconds % 60L;

    if (hours > 0L) {
      return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
    }
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
  }
}
