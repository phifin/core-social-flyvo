package com.flyvo.socialcore.platform.api;

import java.util.UUID;

/** Technical immutable append/verification seam. No business interpretation. */
public interface RecoveryArchive {
  int MAX_RECORD_BYTES = 16384;

  record Receipt(UUID recordId, String digest) {}

  Receipt append(UUID recordId, String digest, byte[] canonicalRecord);

  void verify(Receipt receipt);
}
