package com.flyvo.socialcore.platform.infrastructure.security;

import com.flyvo.socialcore.platform.api.Secrets;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ProtectedSecrets implements Secrets {
  private final SecureRandom random = new SecureRandom();
  private final byte[] key;
  private final byte[] limiterKey;
  private final String keyId;

  public ProtectedSecrets(Environment env) throws Exception {
    boolean ephemeral = env.getProperty("app.security.ephemeral-keys", Boolean.class, false);
    if (ephemeral && !env.matchesProfiles("local", "test"))
      throw new IllegalStateException("Ephemeral keys require local/test profile");
    key = load(env, "app.security.encryption-key-file", ephemeral);
    limiterKey = load(env, "app.security.limiter-key-file", ephemeral);
    keyId = env.getProperty("app.security.encryption-key-id", "primary");
  }

  private byte[] load(Environment env, String property, boolean ephemeral) throws Exception {
    byte[] result;
    if (ephemeral) {
      result = new byte[32];
      random.nextBytes(result);
    } else
      result =
          Base64.getDecoder()
              .decode(Files.readString(Path.of(env.getRequiredProperty(property))).trim());
    if (result.length != 32) throw new IllegalStateException("Keys must have 256 bits");
    return result;
  }

  public String randomToken() {
    byte[] value = new byte[32];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Hash unavailable");
    }
  }

  public byte[] emailKey(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(limiterKey, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC unavailable");
    }
  }

  public byte[] encrypt(String purpose, String value) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(12 + encrypted.length).put(iv).put(encrypted).array();
    } catch (Exception e) {
      throw new IllegalStateException("Encryption unavailable");
    }
  }

  public String decrypt(String purpose, byte[] value) {
    try {
      var buffer = ByteBuffer.wrap(value);
      byte[] iv = new byte[12];
      buffer.get(iv);
      byte[] encrypted = new byte[buffer.remaining()];
      buffer.get(encrypted);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid encrypted payload");
    }
  }

  public String keyId() {
    return keyId;
  }
}
