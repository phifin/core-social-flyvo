package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Real artifact prerequisite, not a mock adapter or a Phase 4 completion claim. */
@Testcontainers
class StorageContractIT {
  static final String IMAGE =
      "docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46";
  static final String ADMIN = "social-admin";
  static final String ADMIN_SECRET = secret();
  static final String RUNTIME = "social-worker";
  static final String RUNTIME_SECRET = secret();
  static final String API = "social-api";
  static final String API_SECRET = secret();
  static final String QUARANTINE = "social-quarantine";
  static final String DELIVERY = "social-delivery";
  static final String ORIGIN = "http://localhost:3000";

  @Container
  static final GenericContainer<?> SILO =
      new GenericContainer<>(IMAGE)
          .withEnv("MINIO_ROOT_USER", ADMIN)
          .withEnv("MINIO_ROOT_PASSWORD", ADMIN_SECRET)
          .withEnv("MINIO_KMS_SECRET_KEY", "test-key:" + secret())
          .withCommand("server", "/data", "--console-address", ":9001")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
          .withStartupTimeout(Duration.ofSeconds(60));

  static S3Client admin;
  static S3Client runtime;
  static S3Client api;
  static S3Presigner presigner;
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void configureStorage() throws Exception {
    admin = client(ADMIN, ADMIN_SECRET);
    SILO.copyFileToContainer(
        org.testcontainers.utility.MountableFile.forHostPath("ops/local/storage"), "/opt/storage");
    SILO.copyFileToContainer(Transferable.of(ADMIN_SECRET, 0600), "/run/secrets/storage-admin.b64");
    SILO.copyFileToContainer(Transferable.of(API_SECRET, 0600), "/run/secrets/storage-api.b64");
    SILO.copyFileToContainer(
        Transferable.of(RUNTIME_SECRET, 0600), "/run/secrets/storage-worker.b64");
    var init =
        SILO.execInContainer(
            "env",
            "FRONTEND_ORIGIN=" + ORIGIN,
            "STORAGE_ENDPOINT=http://localhost:9000",
            "sh",
            "/opt/storage/init.sh");
    assertThat(init.getExitCode()).as("shared Compose bootstrap").isZero();
    runtime = client(RUNTIME, RUNTIME_SECRET);
    api = client(API, API_SECRET);
    presigner =
        S3Presigner.builder()
            .endpointOverride(endpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(API, API_SECRET)))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build())
            .build();
  }

  @AfterAll
  static void closeClients() {
    if (presigner != null) presigner.close();
    if (runtime != null) runtime.close();
    if (api != null) api.close();
    if (admin != null) admin.close();
  }

  @Test
  void privateEncryptedVersionedBucketsAndRestrictedRuntimeCredentials() throws Exception {
    for (String bucket : new String[] {QUARANTINE, DELIVERY}) {
      assertThat(admin.getBucketVersioning(r -> r.bucket(bucket)).status())
          .isEqualTo(BucketVersioningStatus.ENABLED);
      assertThat(
              admin
                  .getBucketEncryption(r -> r.bucket(bucket))
                  .serverSideEncryptionConfiguration()
                  .rules())
          .allSatisfy(
              rule ->
                  assertThat(rule.applyServerSideEncryptionByDefault().sseAlgorithm())
                      .isEqualTo(ServerSideEncryption.AES256));
    }
    String key = key();
    var written =
        api.putObject(r -> r.bucket(QUARANTINE).key(key), RequestBody.fromString("private"));
    assertThat(written.versionId()).isNotBlank();
    assertThat(admin.headObject(r -> r.bucket(QUARANTINE).key(key)).serverSideEncryption())
        .isEqualTo(ServerSideEncryption.AES256);
    assertThat(
            send(HttpRequest.newBuilder(endpoint().resolve("/" + QUARANTINE + "/" + key)).GET())
                .statusCode())
        .isEqualTo(403);
    assertThatThrownBy(() -> runtime.listBuckets()).isInstanceOf(S3Exception.class);
    assertThatThrownBy(() -> api.listObjectVersions(r -> r.bucket(QUARANTINE).prefix("incoming/")))
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(() -> api.deleteObject(r -> r.bucket(QUARANTINE).key(key)))
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(
            () ->
                runtime.putObject(
                    r -> r.bucket(QUARANTINE).key(key), RequestBody.fromString("denied")))
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(() -> runtime.putBucketPolicy(r -> r.bucket(QUARANTINE).policy("{}")))
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(
            () ->
                api.putObject(
                    r -> r.bucket(QUARANTINE).key("forbidden"), RequestBody.fromString("denied")))
        .isInstanceOf(S3Exception.class);
  }

  @Test
  void exactCorsAllowsFrontendPutAndDoesNotGrantForeignOrigin() throws Exception {
    var cors = admin.getBucketCors(r -> r.bucket(QUARANTINE)).corsRules();
    assertThat(cors).hasSize(1);
    assertThat(cors.getFirst().allowedOrigins()).containsExactly(ORIGIN);
    URI uri = endpoint().resolve("/" + QUARANTINE + "/" + key());
    var allowed =
        send(
            HttpRequest.newBuilder(uri)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-amz-checksum-sha256"));
    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.headers().firstValue("access-control-allow-origin")).contains(ORIGIN);
    var denied =
        send(
            HttpRequest.newBuilder(uri)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "PUT"));
    assertThat(denied.headers().firstValue("access-control-allow-origin")).isEmpty();
  }

  @Test
  void checksumPutPinsVersionDespiteReusableUrlOverwriteAndTampering() throws Exception {
    String key = key();
    byte[] original = "validated bytes".getBytes(StandardCharsets.UTF_8);
    var put = upload(key, original);
    assertThat(put.signedHeaders()).containsKey("x-amz-checksum-sha256");
    var first = send(putRequest(put, original));
    assertThat(first.statusCode()).isEqualTo(200);
    String version = first.headers().firstValue("x-amz-version-id").orElseThrow();
    assertThat(
            runtime
                .headObject(
                    r ->
                        r.bucket(QUARANTINE)
                            .key(key)
                            .versionId(version)
                            .checksumMode(ChecksumMode.ENABLED))
                .checksumSHA256())
        .isEqualTo(checksum(original));
    byte[] later = "later overwrite".getBytes(StandardCharsets.UTF_8);
    var second = send(putRequest(upload(key, later), later));
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.headers().firstValue("x-amz-version-id").orElseThrow()).isNotEqualTo(version);
    assertThat(
            runtime
                .getObjectAsBytes(r -> r.bucket(QUARANTINE).key(key).versionId(version))
                .asByteArray())
        .isEqualTo(original);
    assertThat(send(putRequest(put, later)).statusCode()).isBetween(400, 499);
    String damaged = put.url().toString().replace("X-Amz-Signature=", "X-Amz-Signature=0");
    assertThat(send(putRequest(put, original).uri(URI.create(damaged))).statusCode())
        .isBetween(400, 499);
  }

  @Test
  void unsignedOperationCopyAndEncryptionMetadataAreRejected() throws Exception {
    byte[] bytes = "signed content".getBytes(StandardCharsets.UTF_8);
    for (var header :
        Map.of(
                "x-amz-copy-source",
                "/" + QUARANTINE + "/incoming/nonexistent",
                "x-amz-tagging",
                "unexpected=true",
                "x-amz-server-side-encryption",
                "AES256")
            .entrySet()) {
      String key = key();
      var put = upload(key, bytes);
      var response = send(putRequest(put, bytes).header(header.getKey(), header.getValue()));
      assertThat(response.statusCode())
          .as("unsigned header %s", header.getKey())
          .isBetween(400, 499);
      assertThatThrownBy(() -> admin.headObject(r -> r.bucket(QUARANTINE).key(key)))
          .isInstanceOf(NoSuchKeyException.class);
    }
  }

  @Test
  void untrustedReplicationEncryptionHeadersCannotCorruptEncryptedBytes() throws Exception {
    byte[] bytes = "encrypted content".getBytes(StandardCharsets.UTF_8);
    String key = key();
    var response =
        send(
            putRequest(upload(key, bytes), bytes)
                .header("x-minio-internal-server-side-encryption-sealed-key", "untrusted")
                .header("x-minio-replication-server-side-encryption-sealed-key", "untrusted")
                .header("x-minio-replication-server-side-encryption-seal-algorithm", "untrusted")
                .header("x-minio-replication-server-side-encryption-iv", "untrusted"));
    // The patch ignores replication-only metadata on ordinary uploads, rather
    // than rejecting every unknown x-minio header. Verify safe bytes, not a
    // guessed status-code contract (x-amz operation headers are denied above).
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(admin.getObjectAsBytes(r -> r.bucket(QUARANTINE).key(key)).asByteArray())
        .isEqualTo(bytes);
    assertThat(admin.headObject(r -> r.bucket(QUARANTINE).key(key)).serverSideEncryption())
        .isEqualTo(ServerSideEncryption.AES256);
  }

  @Test
  void unsignedTrailerAndSnowballDoNotBypassInvalidSignature() throws Exception {
    for (boolean snowball : new boolean[] {false, true}) {
      String prefix = "incoming/" + UUID.randomUUID() + "/";
      String extracted = prefix + "entry.txt";
      byte[] data = snowball ? archive() : "untrusted bytes".getBytes(StandardCharsets.UTF_8);
      var body = new java.io.ByteArrayOutputStream();
      body.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
      body.write(data);
      body.write(
          ("\r\n0\r\nx-amz-checksum-sha256:" + checksum(data) + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      String key = prefix + "archive.tar";
      var unsigned =
          software.amazon.awssdk.http.SdkHttpFullRequest.builder()
              .uri(endpoint().resolve("/" + QUARANTINE + "/" + key))
              .method(software.amazon.awssdk.http.SdkHttpMethod.PUT)
              .putHeader("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
              .putHeader("x-amz-decoded-content-length", Integer.toString(data.length))
              .putHeader("x-amz-trailer", "x-amz-checksum-sha256")
              .putHeader("Content-Encoding", "aws-chunked");
      if (snowball)
        unsigned
            .putHeader("x-amz-meta-snowball-auto-extract", "true")
            .putHeader("x-amz-meta-minio-snowball-prefix", prefix);
      // The SDK signs the streaming payload marker; it does not invent test crypto.
      var signed =
          software.amazon.awssdk.auth.signer.AwsS3V4Signer.create()
              .sign(
                  unsigned.build(),
                  software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams.builder()
                      .awsCredentials(AwsBasicCredentials.create(API, API_SECRET))
                      .signingRegion(Region.US_EAST_1)
                      .signingName("s3")
                      .enablePayloadSigning(false)
                      .enableChunkedEncoding(false)
                      .build());
      String auth = signed.firstMatchingHeader("Authorization").orElseThrow();
      // Exercise both advisories BEFORE the positive control on exactly the same
      // key, bytes and signed operation headers. No missing-header false positives.
      for (boolean queryOnly : new boolean[] {false, true}) {
        URI uri = signed.getUri();
        if (queryOnly) {
          String credential = auth.substring(auth.indexOf("Credential=") + 11, auth.indexOf(","));
          String headers =
              auth.substring(auth.indexOf("SignedHeaders=") + 14, auth.indexOf(", Signature="));
          uri =
              URI.create(
                  uri
                      + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential="
                      + java.net.URLEncoder.encode(credential, StandardCharsets.UTF_8)
                      + "&X-Amz-Date="
                      + signed.firstMatchingHeader("x-amz-date").orElseThrow()
                      + "&X-Amz-Expires=600&X-Amz-SignedHeaders="
                      + java.net.URLEncoder.encode(headers, StandardCharsets.UTF_8)
                      + "&X-Amz-Signature="
                      + "0".repeat(64));
        }
        var request = trailerRequest(signed, uri, body.toByteArray(), false);
        if (!queryOnly)
          request.header(
              "Authorization",
              auth.replaceAll("Signature=[0-9a-f]+", "Signature=" + "0".repeat(64)));
        assertThat(request.build().headers().firstValue("Authorization").isEmpty())
            .isEqualTo(queryOnly);
        var denied = send(request);
        String errorCode =
            new String(denied.body(), StandardCharsets.UTF_8)
                .replaceAll("(?s).*<Code>([^<]+)</Code>.*", "$1");
        assertThat(denied.statusCode())
            .as(
                "invalid signature: extract=%s, queryOnly=%s, code=%s",
                snowball, queryOnly, errorCode)
            .isEqualTo(queryOnly ? 400 : 403);
        assertThat(errorCode)
            .as("extract=%s, queryOnly=%s", snowball, queryOnly)
            .isEqualTo(queryOnly ? "InvalidRequest" : "SignatureDoesNotMatch");
        if (queryOnly) {
          // Tagged Silo explicitly rejects presigned auth for unsigned trailers
          // before consuming bytes (ErrSignatureVersionNotSupported).
          assertThat(new String(denied.body(), StandardCharsets.UTF_8))
              .contains("The authorization mechanism you have provided is not supported");
        }
        assertThat(admin.listObjectVersions(r -> r.bucket(QUARANTINE).prefix(prefix)).versions())
            .isEmpty();
        assertThatThrownBy(() -> admin.headObject(r -> r.bucket(QUARANTINE).key(extracted)))
            .isInstanceOf(NoSuchKeyException.class);
      }
      var positive = send(trailerRequest(signed, signed.getUri(), body.toByteArray(), true));
      assertThat(positive.statusCode())
          .as("well-formed valid control: extract=%s", snowball)
          .isEqualTo(200);
      assertThat(
              admin
                  .getObjectAsBytes(r -> r.bucket(QUARANTINE).key(snowball ? extracted : key))
                  .asByteArray())
          .isEqualTo(snowball ? "fixture".getBytes(StandardCharsets.UTF_8) : data);
    }
  }

  static HttpRequest.Builder trailerRequest(
      software.amazon.awssdk.http.SdkHttpFullRequest signed,
      URI uri,
      byte[] body,
      boolean includeAuth) {
    var request = HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
    signed
        .headers()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("host")
                  && (includeAuth || !name.equalsIgnoreCase("Authorization")))
                values.forEach(value -> request.header(name, value));
            });
    return request;
  }

  static byte[] archive() {
    // A nonempty POSIX ustar: one seven-byte entry, padded blocks and two end blocks.
    byte[] tar = new byte[2048];
    tarField(tar, 0, "entry.txt");
    tarField(tar, 100, "0000600");
    tarField(tar, 108, "0000000");
    tarField(tar, 116, "0000000");
    tarField(tar, 124, "00000000007");
    tarField(tar, 136, "00000000000");
    java.util.Arrays.fill(tar, 148, 156, (byte) ' ');
    tar[156] = '0';
    tarField(tar, 257, "ustar");
    tarField(tar, 263, "00");
    int sum = 0;
    for (int i = 0; i < 512; i++) sum += Byte.toUnsignedInt(tar[i]);
    tarField(tar, 148, String.format(java.util.Locale.ROOT, "%06o", sum));
    tar[155] = ' ';
    tarField(tar, 512, "fixture");
    return tar;
  }

  static void tarField(byte[] tar, int offset, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, tar, offset, bytes.length);
  }

  @Test
  void existingObjectsOutsideAllowedPrefixAreDeniedToAuthenticatedRuntime() {
    String key = "forbidden/" + UUID.randomUUID();
    admin.putObject(
        r -> r.bucket(DELIVERY).key(key), RequestBody.fromString("restricted synthetic bytes"));
    assertThatThrownBy(() -> api.getObjectAsBytes(r -> r.bucket(DELIVERY).key(key)))
        .as("API credential has no GetObject grant for delivery/forbidden")
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(() -> runtime.getObjectAsBytes(r -> r.bucket(DELIVERY).key(key)))
        .as("worker credential has no GetObject grant for delivery/forbidden")
        .isInstanceOf(S3Exception.class);
  }

  @Test
  void deliveryIsPrivateVersionOnlyAndCapabilitiesExpireOnRealStorage() throws Exception {
    String key = "variants/" + UUID.randomUUID() + "/1/display.png";
    var written =
        runtime.putObject(
            r -> r.bucket(DELIVERY).key(key).contentType("image/png"),
            RequestBody.fromString("processed variant"));
    assertThat(
            send(HttpRequest.newBuilder(endpoint().resolve("/" + DELIVERY + "/" + key)).GET())
                .statusCode())
        .isEqualTo(403);
    assertThatThrownBy(() -> api.getObject(r -> r.bucket(DELIVERY).key(key)))
        .isInstanceOf(S3Exception.class);
    var capability =
        presigner.presignGetObject(
            r ->
                r.signatureDuration(Duration.ofSeconds(60))
                    .getObjectRequest(
                        g -> g.bucket(DELIVERY).key(key).versionId(written.versionId())));
    assertThat(capability.httpRequest().rawQueryParameters().get("X-Amz-Expires"))
        .containsExactly("60");
    assertThat(
            send(HttpRequest.newBuilder(URI.create(capability.url().toString())).GET())
                .statusCode())
        .isEqualTo(200);
    assertThat(
            send(HttpRequest.newBuilder(URI.create(capability.url().toString()))
                    .header("x-amz-copy-source", "/" + DELIVERY + "/" + key)
                    .GET())
                .statusCode())
        .isBetween(400, 499);
    var expiring =
        presigner.presignGetObject(
            r ->
                r.signatureDuration(Duration.ofSeconds(1))
                    .getObjectRequest(
                        g -> g.bucket(DELIVERY).key(key).versionId(written.versionId())));
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        send(HttpRequest.newBuilder(URI.create(expiring.url().toString())).GET())
                            .statusCode())
                    .isEqualTo(403));
  }

  @Test
  void bootstrapIsIdempotentAndQuarantineLifecycleBoundsVersions() throws Exception {
    var init =
        SILO.execInContainer(
            "env",
            "FRONTEND_ORIGIN=" + ORIGIN,
            "STORAGE_ENDPOINT=http://localhost:9000",
            "sh",
            "/opt/storage/init.sh");
    assertThat(init.getExitCode()).isZero();
    var lifecycle = admin.getBucketLifecycleConfiguration(r -> r.bucket(QUARANTINE)).rules();
    assertThat(lifecycle).hasSize(1);
    assertThat(lifecycle.getFirst().expiration().days()).isEqualTo(2);
    assertThat(lifecycle.getFirst().noncurrentVersionExpiration().noncurrentDays()).isEqualTo(2);
    assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("docker-compose.yml")))
        .contains(IMAGE);
  }

  @Test
  void adapterPinsUploadAndWritesImmutableRetryableVariantsWithBoundedAccess() throws Exception {
    var storage =
        new com.flyvo.socialcore.media.infrastructure.storage.S3ObjectStorage(
            api, runtime, presigner, QUARANTINE, DELIVERY);
    byte[] bytes = "synthetic encoded bytes".getBytes(StandardCharsets.UTF_8);
    String key = key();
    var upload = storage.upload(key, "image/png", bytes.length, checksum(bytes));
    var request =
        HttpRequest.newBuilder(URI.create(upload.url()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    upload
        .headers()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("content-length"))
                values.forEach(value -> request.header(name, value));
            });
    var response = send(request);
    assertThat(response.statusCode()).isEqualTo(200);
    String version = response.headers().firstValue("x-amz-version-id").orElseThrow();
    var info = storage.inspectUpload(key, version);
    assertThat(info.bytes()).isEqualTo(bytes.length);
    assertThat(info.sha256()).isEqualTo(checksum(bytes));
    assertThat(storage.readUpload(key, version, bytes.length)).isEqualTo(bytes);
    var prefix = "variants/" + UUID.randomUUID() + "/";
    String variantKey = prefix + "1/display.png";
    var variant = storage.writeVariant(variantKey, "image/png", bytes);
    assertThat(storage.writeVariant(variantKey, "image/png", bytes)).isEqualTo(variant);
    assertThatThrownBy(() -> storage.writeVariant(variantKey, "image/png", new byte[] {1}))
        .isInstanceOf(com.flyvo.socialcore.platform.api.Failure.class);
    var capability = storage.variantAccess(variantKey, variant.versionId(), Duration.ofSeconds(60));
    assertThat(send(HttpRequest.newBuilder(URI.create(capability)).GET()).body()).isEqualTo(bytes);
    assertThatThrownBy(
            () -> storage.variantAccess(variantKey, variant.versionId(), Duration.ofSeconds(61)))
        .isInstanceOf(com.flyvo.socialcore.platform.api.Failure.class);
    assertThatThrownBy(() -> storage.readUpload(key, null, bytes.length))
        .isInstanceOf(com.flyvo.socialcore.platform.api.Failure.class);
    assertThat(storage.purge(key, prefix)).isTrue();
    assertThat(storage.purge(key, prefix)).isTrue();
    assertThatThrownBy(
            () ->
                runtime.getObject(
                    r -> r.bucket(DELIVERY).key(variantKey).versionId(variant.versionId())))
        .isInstanceOf(S3Exception.class);
    assertThatThrownBy(() -> storage.inspectUpload(key, version))
        .isInstanceOf(com.flyvo.socialcore.platform.api.Failure.class);
  }

  @Test
  void adapterRejectsStorageNetworkCallsInsideDatabaseTransactionBoundary() {
    var storage =
        new com.flyvo.socialcore.media.infrastructure.storage.S3ObjectStorage(
            api, runtime, presigner, QUARANTINE, DELIVERY);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> storage.inspectUpload(key(), UUID.randomUUID().toString()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> storage.readUpload(key(), UUID.randomUUID().toString(), 1))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () -> storage.writeVariant("variants/a/1/display.png", "image/png", new byte[] {1}))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> storage.purge(key(), "variants/a/"))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
  }

  @Test
  void allVersionsAndDeleteMarkersCanBePurgedAndAbsenceConfirmed() {
    String key = key();
    for (int i = 0; i < 3; i++)
      api.putObject(r -> r.bucket(QUARANTINE).key(key), RequestBody.fromString("version" + i));
    runtime.deleteObject(r -> r.bucket(QUARANTINE).key(key));
    var before = runtime.listObjectVersions(r -> r.bucket(QUARANTINE).prefix(key));
    assertThat(before.versions()).hasSize(3);
    assertThat(before.deleteMarkers()).hasSize(1);
    before
        .versions()
        .forEach(
            v ->
                runtime.deleteObject(
                    r -> r.bucket(QUARANTINE).key(v.key()).versionId(v.versionId())));
    before
        .deleteMarkers()
        .forEach(
            v ->
                runtime.deleteObject(
                    r -> r.bucket(QUARANTINE).key(v.key()).versionId(v.versionId())));
    var after = runtime.listObjectVersions(r -> r.bucket(QUARANTINE).prefix(key));
    assertThat(after.versions()).isEmpty();
    assertThat(after.deleteMarkers()).isEmpty();
    assertThatThrownBy(() -> admin.headObject(r -> r.bucket(QUARANTINE).key(key)))
        .isInstanceOf(NoSuchKeyException.class);
    before
        .versions()
        .forEach(
            v ->
                assertThatThrownBy(
                        () ->
                            admin.getObject(
                                r -> r.bucket(QUARANTINE).key(key).versionId(v.versionId())))
                    .isInstanceOf(S3Exception.class));
  }

  static PresignedPutObjectRequest upload(String key, byte[] bytes) throws Exception {
    String sha256 = checksum(bytes);
    return presigner.presignPutObject(
        r ->
            r.signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(
                    p ->
                        p.bucket(QUARANTINE)
                            .key(key)
                            .contentType("image/png")
                            .checksumSHA256(sha256)));
  }

  static HttpRequest.Builder putRequest(PresignedPutObjectRequest put, byte[] bytes) {
    var request =
        HttpRequest.newBuilder(URI.create(put.url().toString()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    put.signedHeaders()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("host"))
                values.forEach(value -> request.header(name, value));
            });
    return request;
  }

  static HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
    return HTTP.send(
        request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  static String checksum(byte[] bytes) throws Exception {
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static String key() {
    return "incoming/" + UUID.randomUUID();
  }

  static URI endpoint() {
    return URI.create("http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
  }

  static S3Client client(String user, String password) {
    return S3Client.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password)))
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(10)))
        .overrideConfiguration(
            c ->
                c.apiCallTimeout(Duration.ofSeconds(15))
                    .apiCallAttemptTimeout(Duration.ofSeconds(10)))
        .forcePathStyle(true)
        .build();
  }

  static String secret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
