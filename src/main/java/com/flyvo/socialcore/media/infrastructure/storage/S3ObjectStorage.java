package com.flyvo.socialcore.media.infrastructure.storage;

import com.flyvo.socialcore.media.application.port.ObjectStorage;
import com.flyvo.socialcore.platform.api.Failure;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** SDK clients must have bounded connect/read/API deadlines; no credentials are exposed here. */
public final class S3ObjectStorage implements ObjectStorage {
  private final S3Client api;
  private final S3Client worker;
  private final S3Presigner signer;
  private final String quarantine;
  private final String delivery;

  public S3ObjectStorage(
      S3Client api, S3Client worker, S3Presigner signer, String quarantine, String delivery) {
    this.api = api;
    this.worker = worker;
    this.signer = signer;
    this.quarantine = quarantine;
    this.delivery = delivery;
  }

  public Upload upload(String key, String mime, long bytes, String sha256) {
    return upload(key, mime, bytes, sha256, Duration.ofMinutes(10));
  }

  public Upload upload(String key, String mime, long bytes, String sha256, Duration ttl) {
    key(key, "incoming/");
    if (bytes < 1
        || bytes > 10 * 1024 * 1024
        || !List.of("image/jpeg", "image/png", "image/webp").contains(mime == null ? "" : mime))
      throw Failure.invalid();
    try {
      byte[] digest = Base64.getDecoder().decode(sha256);
      if (digest.length != 32 || !Base64.getEncoder().encodeToString(digest).equals(sha256))
        throw Failure.invalid();
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw Failure.invalid();
    }
    if (ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofMinutes(10)) > 0) throw Failure.invalid();
    var signed =
        signer.presignPutObject(
            r ->
                r.signatureDuration(ttl)
                    .putObjectRequest(
                        p ->
                            p.bucket(quarantine)
                                .key(key)
                                .contentType(mime)
                                .contentLength(bytes)
                                .checksumSHA256(sha256)));
    var headers = new LinkedHashMap<String, List<String>>();
    signed
        .signedHeaders()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("host")) headers.put(name, List.copyOf(values));
            });
    return new Upload(signed.url().toString(), Map.copyOf(headers));
  }

  public ObjectInfo inspectUpload(String key, String versionId) {
    outsideTransaction();
    key(key, "incoming/");
    version(versionId);
    try {
      return info(
          api.headObject(
              r ->
                  r.bucket(quarantine)
                      .key(key)
                      .versionId(versionId)
                      .checksumMode(ChecksumMode.ENABLED)));
    } catch (S3Exception failure) {
      if (failure.statusCode() == 404) throw new Failure(409, "UPLOAD_NOT_FOUND");
      throw unavailable();
    } catch (software.amazon.awssdk.core.exception.SdkException failure) {
      throw unavailable();
    }
  }

  public byte[] readUpload(String key, String versionId, long ceiling) {
    outsideTransaction();
    key(key, "incoming/");
    version(versionId);
    if (ceiling < 1 || ceiling > 10 * 1024 * 1024) throw Failure.invalid();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    try (var input = worker.getObject(r -> r.bucket(quarantine).key(key).versionId(versionId));
        var output = new ByteArrayOutputStream()) {
      if (input.response().contentLength() > ceiling) {
        input.abort();
        throw new Failure(413, "IMAGE_TOO_LARGE");
      }
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (System.nanoTime() > deadline) {
          input.abort();
          throw unavailable();
        }
        if (output.size() + read > ceiling) {
          input.abort();
          throw new Failure(413, "IMAGE_TOO_LARGE");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (Failure failure) {
      throw failure;
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  public ObjectInfo writeVariant(String key, String mime, byte[] bytes) {
    outsideTransaction();
    key(key, "variants/");
    if (bytes == null
        || bytes.length == 0
        || bytes.length > 10 * 1024 * 1024
        || !List.of("image/jpeg", "image/png").contains(mime)) throw Failure.invalid();
    String sha256 = checksum(bytes);
    try {
      var written =
          worker.putObject(
              r ->
                  r.bucket(delivery)
                      .key(key)
                      .contentType(mime)
                      .checksumSHA256(sha256)
                      .ifNoneMatch("*"),
              RequestBody.fromBytes(bytes));
      version(written.versionId());
      return new ObjectInfo(written.versionId(), mime, bytes.length, sha256);
    } catch (S3Exception failure) {
      if (failure.statusCode() != 412 && failure.statusCode() != 409) throw unavailable();
      // Retry after ambiguous successful PUT: reuse only exactly matching immutable output.
      ObjectInfo existing;
      try {
        existing =
            info(
                worker.headObject(
                    r -> r.bucket(delivery).key(key).checksumMode(ChecksumMode.ENABLED)));
      } catch (software.amazon.awssdk.core.exception.SdkException unavailable) {
        throw unavailable();
      }
      if (existing.bytes() != bytes.length
          || !mime.equals(existing.contentType())
          || !sha256.equals(existing.sha256()))
        throw new Failure(409, "VARIANT_IMMUTABILITY_CONFLICT");
      return existing;
    } catch (software.amazon.awssdk.core.exception.SdkException failure) {
      throw unavailable();
    }
  }

  public String variantAccess(String key, String versionId, Duration ttl) {
    key(key, "variants/");
    version(versionId);
    if (ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofSeconds(60)) > 0) throw Failure.invalid();
    return signer
        .presignGetObject(
            r ->
                r.signatureDuration(ttl)
                    .getObjectRequest(g -> g.bucket(delivery).key(key).versionId(versionId)))
        .url()
        .toString();
  }

  public boolean purge(String quarantineKey, String variantPrefix) {
    outsideTransaction();
    key(quarantineKey, "incoming/");
    key(variantPrefix, "variants/");
    if (!variantPrefix.endsWith("/")) throw Failure.invalid();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    try {
      return purge(quarantine, quarantineKey, true, deadline)
          & purge(delivery, variantPrefix, false, deadline);
    } catch (software.amazon.awssdk.core.exception.SdkException failure) {
      throw unavailable();
    }
  }

  private boolean purge(String bucket, String prefix, boolean exact, long deadline) {
    withinDeadline(deadline);
    var page = worker.listObjectVersions(r -> r.bucket(bucket).prefix(prefix).maxKeys(50));
    page.versions().stream()
        .filter(v -> !exact || v.key().equals(prefix))
        .forEach(
            v -> {
              withinDeadline(deadline);
              worker.deleteObject(r -> r.bucket(bucket).key(v.key()).versionId(v.versionId()));
            });
    page.deleteMarkers().stream()
        .filter(v -> !exact || v.key().equals(prefix))
        .forEach(
            v -> {
              withinDeadline(deadline);
              worker.deleteObject(r -> r.bucket(bucket).key(v.key()).versionId(v.versionId()));
            });
    if (page.isTruncated()) return false;
    for (var version : page.versions()) {
      if (exact && !version.key().equals(prefix)) continue;
      withinDeadline(deadline);
      try {
        worker.headObject(r -> r.bucket(bucket).key(version.key()).versionId(version.versionId()));
        return false;
      } catch (S3Exception absent) {
        if (absent.statusCode() != 404) throw absent;
      }
    }
    withinDeadline(deadline);
    var after = worker.listObjectVersions(r -> r.bucket(bucket).prefix(prefix).maxKeys(50));
    return !after.isTruncated()
        && after.versions().stream().noneMatch(v -> !exact || v.key().equals(prefix))
        && after.deleteMarkers().stream().noneMatch(v -> !exact || v.key().equals(prefix));
  }

  public boolean purgeVariants(String prefix) {
    outsideTransaction();
    key(prefix, "variants/");
    if (!prefix.endsWith("/")) throw Failure.invalid();
    try {
      return purge(delivery, prefix, false, System.nanoTime() + Duration.ofSeconds(20).toNanos());
    } catch (software.amazon.awssdk.core.exception.SdkException failure) {
      throw unavailable();
    }
  }

  public boolean purgeUpload(String key) {
    outsideTransaction();
    key(key, "incoming/");
    try {
      return purge(quarantine, key, true, System.nanoTime() + Duration.ofSeconds(20).toNanos());
    } catch (software.amazon.awssdk.core.exception.SdkException failure) {
      throw unavailable();
    }
  }

  private static void withinDeadline(long deadline) {
    if (System.nanoTime() > deadline) throw unavailable();
  }

  private static ObjectInfo info(HeadObjectResponse value) {
    version(value.versionId());
    return new ObjectInfo(
        value.versionId(), value.contentType(), value.contentLength(), value.checksumSHA256());
  }

  private static void key(String value, String prefix) {
    if (value == null
        || !value.startsWith(prefix)
        || value.length() <= prefix.length()
        || value.length() > 512
        || !value.matches("[a-zA-Z0-9_./-]+")
        || value.contains("..")) throw Failure.invalid();
  }

  private static void version(String value) {
    if (value == null || value.isBlank() || value.equals("null") || value.length() > 256)
      throw Failure.invalid();
  }

  private static void outsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("STORAGE_CALL_INSIDE_TRANSACTION");
  }

  private static Failure unavailable() {
    return new Failure(503, "STORAGE_UNAVAILABLE");
  }

  private static String checksum(byte[] bytes) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
