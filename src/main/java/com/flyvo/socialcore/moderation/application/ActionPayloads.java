package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.platform.api.Secrets;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local loaded-key crypto only; callers own scope authorization and root locks. */
@Component
public class ActionPayloads {
  public record Detail(String reason, JsonNode before, JsonNode after) {
    @Override
    public String toString() {
      return "ModerationDetail[redacted]";
    }
  }

  public record Encrypted(byte[] ciphertext, String keyId) {
    public Encrypted {
      ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext.clone();
    }
  }

  private final Secrets secrets;
  private final ObjectMapper json;

  public ActionPayloads(Secrets secrets, ObjectMapper json) {
    this.secrets = secrets;
    this.json = json;
  }

  public String detail(String reason, String before, String after) {
    return json.writeValueAsString(new Detail(reason, json.readTree(before), json.readTree(after)));
  }

  public Encrypted encrypt(UUID id, String legacy) {
    var ciphertext = secrets.encrypt("moderation-action:" + id, legacy);
    if (ciphertext.length > 65536
        || !json.readTree(legacy)
            .equals(json.readTree(secrets.decrypt("moderation-action:" + id, ciphertext))))
      throw new IllegalStateException("MODERATION_ENCRYPTION_VERIFICATION_FAILED");
    return new Encrypted(ciphertext, secrets.keyId());
  }

  public Detail read(UUID id, String legacy, byte[] ciphertext) {
    return json.readValue(
        legacy != null ? legacy : secrets.decrypt("moderation-action:" + id, ciphertext),
        Detail.class);
  }
}
