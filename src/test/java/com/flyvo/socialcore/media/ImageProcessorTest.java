package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.media.infrastructure.processing.IsolatedImageProcessor;
import com.flyvo.socialcore.platform.api.Failure;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {
  final IsolatedImageProcessor processor = new IsolatedImageProcessor();

  byte[] image(String format, int w, int h) throws Exception {
    var im =
        new BufferedImage(
            w, h, format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
    im.setRGB(0, 0, 0xff123456);
    var out = new ByteArrayOutputStream();
    ImageIO.write(im, format, out);
    return out.toByteArray();
  }

  byte[] chunk(String type, byte[] data) throws Exception {
    var out = new ByteArrayOutputStream();
    var d = new DataOutputStream(out);
    d.writeInt(data.length);
    var t = type.getBytes(StandardCharsets.US_ASCII);
    d.write(t);
    d.write(data);
    var crc = new java.util.zip.CRC32();
    crc.update(t);
    crc.update(data);
    d.writeInt((int) crc.getValue());
    return out.toByteArray();
  }

  byte[] insert(byte[] original, byte[] metadata) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(original, 0, original.length - 12);
    out.write(metadata);
    out.write(original, original.length - 12, 12);
    return out.toByteArray();
  }

  void invalid(byte[] bytes, String mime) {
    assertThatThrownBy(() -> processor.process(bytes, mime))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(415));
  }

  @Test
  void jpegPngAndStaticWebpDecodeInChildAndProduceOnlyBoundedReencodedPng() throws Exception {
    for (String format : List.of("jpeg", "png")) {
      var variants = processor.process(image(format, 4096, 2), "image/" + format);
      assertThat(variants).hasSize(2);
      assertThat(variants.getFirst().width()).isEqualTo(2048);
      assertThat(variants.getLast().width()).isEqualTo(320);
      for (var v : variants)
        assertThat(ImageIO.read(new ByteArrayInputStream(v.bytes()))).isNotNull();
    }
    byte[] webp =
        Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    var variants = processor.process(webp, "image/webp");
    assertThat(variants).hasSize(2);
    assertThat(variants.getFirst().width()).isEqualTo(1);
  }

  @Test
  void
      locationMetadataIsStrippedAndTrailingPolyglotsMismatchesAnimationAndCrcCorruptionAreRejected()
          throws Exception {
    byte[] original = image("png", 32, 16);
    byte[] metadata =
        chunk("tEXt", "GPSLatitude\u0000Synthetic-location".getBytes(StandardCharsets.UTF_8));
    for (var v : processor.process(insert(original, metadata), "image/png"))
      assertThat(new String(v.bytes(), StandardCharsets.ISO_8859_1))
          .doesNotContain("GPSLatitude", "Synthetic-location");
    byte[] jpeg = image("jpeg", 16, 16);
    var exif = new ByteArrayOutputStream();
    exif.write(jpeg, 0, 2);
    exif.write(new byte[] {(byte) 255, (byte) 225, 0, 24});
    exif.write("Exif\u0000\u0000GPSLatitude=test".getBytes(StandardCharsets.US_ASCII));
    exif.write(jpeg, 2, jpeg.length - 2);
    for (var v : processor.process(exif.toByteArray(), "image/jpeg"))
      assertThat(new String(v.bytes(), StandardCharsets.ISO_8859_1))
          .doesNotContain("GPSLatitude", "Exif");
    invalid(Arrays.copyOf(original, original.length + 4), "image/png");
    invalid(Arrays.copyOf(jpeg, jpeg.length + 4), "image/jpeg");
    invalid(original, "image/webp");
    invalid(jpeg, "image/png");
    invalid(insert(original, chunk("acTL", new byte[] {0, 0, 0, 2, 0, 0, 0, 0})), "image/png");
    byte[] corrupt = original.clone();
    corrupt[30] ^= 1;
    invalid(corrupt, "image/png");
    invalid(
        "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8), "image/png");
    byte[] webp =
        Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    var animated = new ByteArrayOutputStream();
    animated.write(webp);
    animated.write("ANIM".getBytes(StandardCharsets.US_ASCII));
    animated.write(new byte[4]);
    byte[] animation = animated.toByteArray();
    ByteBuffer.wrap(animation, 4, 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt(animation.length - 8);
    invalid(animation, "image/webp");
  }

  @Test
  void declaredDimensionsAreCheckedBeforePixelAllocationAndInputCeilingIsEnforced()
      throws Exception {
    byte[] png = image("png", 2, 2);
    byte[] huge = png.clone();
    ByteBuffer.wrap(huge, 16, 8).putInt(40001).putInt(1000);
    var crc = new java.util.zip.CRC32();
    crc.update(huge, 12, 17);
    ByteBuffer.wrap(huge, 29, 4).putInt((int) crc.getValue());
    invalid(huge, "image/png");
    assertThatThrownBy(() -> processor.process(new byte[10485761], "image/png"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(413));
    invalid(Arrays.copyOf(png, png.length - 3), "image/png");
  }
}
