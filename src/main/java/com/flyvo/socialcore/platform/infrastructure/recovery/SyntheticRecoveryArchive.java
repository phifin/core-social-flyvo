package com.flyvo.socialcore.platform.infrastructure.recovery;

import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.RecoveryArchive;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit local protocol fixture, NOT a production failure-domain/latest-cut authority. */
@Component
public class SyntheticRecoveryArchive implements RecoveryArchive {
  private final Path root;

  public SyntheticRecoveryArchive(Environment env) {
    String path = env.getProperty("app.erasure.archive-directory", "");
    if (path.isBlank()) {
      root = null;
      return;
    }
    try {
      var configured = Path.of(path).toAbsolutePath().normalize();
      root = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!root.equals(configured)
          || !root.startsWith(Path.of("/tmp"))
          || !root.getName(1).toString().startsWith("social-p9a-")
          || Files.isSymbolicLink(root)
          || !Files.isDirectory(root)
          || !"isolated-synthetic-v1".equals(env.getProperty("app.erasure.policy")))
        throw new IllegalArgumentException("INVALID_SYNTHETIC_ARCHIVE");
    } catch (Exception e) {
      throw new IllegalStateException("INVALID_SYNTHETIC_ARCHIVE");
    }
  }

  private void boundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("ARCHIVE_UNDER_TRANSACTION");
    if (root == null) throw new Failure(503, "RECOVERY_ARCHIVE_UNAVAILABLE");
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Receipt append(UUID id, String expected, byte[] bytes) {
    boundary();
    if (id == null || bytes == null || bytes.length > 16384 || !digest(bytes).equals(expected))
      throw new IllegalStateException("ARCHIVE_DIGEST_MISMATCH");
    var target = root.resolve(id + ".record");
    Path pending = null;
    try {
      pending = Files.createTempFile(root, "append-", ".pending");
      try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.createLink(target, pending);
      } catch (FileAlreadyExistsException duplicate) {
        /* Verify exact immutable duplicate below. */
      }
      try (var directory = FileChannel.open(root, StandardOpenOption.READ)) {
        directory.force(true);
      }
      var receipt = new Receipt(id, expected);
      verify(receipt);
      return receipt;
    } catch (java.io.IOException e) {
      throw new Failure(503, "RECOVERY_ARCHIVE_UNAVAILABLE");
    } finally {
      if (pending != null)
        try {
          Files.deleteIfExists(pending);
        } catch (java.io.IOException ignored) {
          /* Unpublished append has no authority. */
        }
    }
  }

  public void verify(Receipt receipt) {
    boundary();
    try {
      var file = root.resolve(receipt.recordId() + ".record");
      if (Files.isSymbolicLink(file)
          || Files.size(file) > 16384
          || !digest(Files.readAllBytes(file)).equals(receipt.digest()))
        throw new IllegalStateException("ARCHIVE_INTEGRITY_FAILURE");
    } catch (java.io.IOException e) {
      throw new Failure(503, "RECOVERY_ARCHIVE_UNAVAILABLE");
    }
  }
}
