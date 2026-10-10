package com.flyvo.socialcore.media.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Technical storage boundary. Keys and version IDs originate in media's owned database only. */
public interface ObjectStorage {
  record Upload(String url, Map<String, List<String>> headers) {}

  record ObjectInfo(String versionId, String contentType, long bytes, String sha256) {}

  Upload upload(String key, String mime, long bytes, String sha256);

  Upload upload(String key, String mime, long bytes, String sha256, Duration ttl);

  ObjectInfo inspectUpload(String key, String versionId);

  byte[] readUpload(String key, String versionId, long ceiling);

  ObjectInfo writeVariant(String key, String mime, byte[] bytes);

  String variantAccess(String key, String versionId, Duration ttl);

  /** Deletes only the given asset-owned prefixes, all versions/markers, with bounded work. */
  boolean purge(String quarantineKey, String variantPrefix);

  boolean purgeVariants(String variantPrefix);

  boolean purgeUpload(String quarantineKey);
}
