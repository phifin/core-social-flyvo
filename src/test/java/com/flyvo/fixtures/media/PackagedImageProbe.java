package com.flyvo.fixtures.media;

/** Runs through Boot's packaged loader, then the production isolated child launcher. */
public final class PackagedImageProbe {
  public static void main(String[] args) {
    byte[] webp =
        java.util.Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    var images =
        new com.flyvo.socialcore.media.infrastructure.processing.IsolatedImageProcessor()
            .process(webp, "image/webp");
    if (images.size() != 2
        || images.stream()
            .anyMatch(i -> i.width() != 1 || i.height() != 1 || !i.mime().equals("image/png")))
      throw new AssertionError("PACKAGED_DECODER_FAILED");
    System.out.println("PACKAGED_DECODER_OK");
  }
}
