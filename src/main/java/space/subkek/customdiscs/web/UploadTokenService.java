package space.subkek.customdiscs.web;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UploadTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();

  public CreatedToken createToken(UUID playerId, int ttlSeconds) {
    pruneExpired();

    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);

    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiresAt = Instant.now().plusSeconds(Math.max(ttlSeconds, 1));
    tokens.put(token, new StoredToken(playerId, expiresAt));
    return new CreatedToken(token, expiresAt);
  }

  public ValidationResult consume(String token) {
    pruneExpired();

    if (token == null || token.isBlank()) {
      return ValidationResult.failure(Failure.MISSING_TOKEN);
    }

    StoredToken stored = tokens.remove(token.trim());
    if (stored == null) {
      return ValidationResult.failure(Failure.INVALID_TOKEN);
    }

    if (stored.expiresAt().isBefore(Instant.now())) {
      return ValidationResult.failure(Failure.EXPIRED_TOKEN);
    }

    return ValidationResult.success(stored.playerId());
  }

  public void invalidateAll() {
    tokens.clear();
  }

  private void pruneExpired() {
    Instant now = Instant.now();
    tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  private record StoredToken(UUID playerId, Instant expiresAt) {
  }

  @Getter
  @RequiredArgsConstructor
  public enum Failure {
    MISSING_TOKEN("missing_token"),
    INVALID_TOKEN("invalid_token"),
    EXPIRED_TOKEN("expired_token");

    private final String apiCode;
  }

  public record ValidationResult(boolean success, UUID playerId, Failure failure) {
    public static ValidationResult success(UUID playerId) {
      return new ValidationResult(true, playerId, null);
    }

    public static ValidationResult failure(Failure failure) {
      return new ValidationResult(false, null, failure);
    }
  }

  public record CreatedToken(String token, Instant expiresAt) {
  }
}
