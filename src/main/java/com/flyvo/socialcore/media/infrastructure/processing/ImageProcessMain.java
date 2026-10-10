package com.flyvo.socialcore.media.infrastructure.processing;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Credential-free child JVM entry point; never initializes the application. */
public final class ImageProcessMain {
  private ImageProcessMain() {}

  public static void main(String[] args) {
    try {
      var in = new DataInputStream(System.in);
      String mime = in.readUTF();
      int size = in.readInt();
      require(size > 0 && size <= 10485760);
      byte[] bytes = in.readNBytes(size);
      require(bytes.length == size);
      validateContainer(bytes, mime);
      ImageIO.setUseCache(false);
      try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(stream);
        require(readers.hasNext());
        var reader = readers.next();
        try {
          reader.setInput(stream, false, true);
          int w = reader.getWidth(0), h = reader.getHeight(0);
          require(w > 0 && h > 0 && (long) w * h <= 40000000);
          var image = reader.read(0);
          require(image != null);
          var out = new DataOutputStream(System.out);
          for (int max : new int[] {2048, 320}) {
            double scale = Math.min(1d, Math.min((double) max / w, (double) max / h));
            int dw = Math.max(1, (int) Math.round(w * scale)),
                dh = Math.max(1, (int) Math.round(h * scale));
            var clean = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
            var g = clean.createGraphics();
            try {
              g.setRenderingHint(
                  RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
              g.drawImage(image, 0, 0, dw, dh, null);
            } finally {
              g.dispose();
            }
            var encoded = new ByteArrayOutputStream();
            require(ImageIO.write(clean, "png", encoded));
            require(encoded.size() <= 10485760);
            out.writeInt(dw);
            out.writeInt(dh);
            out.writeInt(encoded.size());
            encoded.writeTo(out);
            clean.flush();
          }
          out.flush();
          image.flush();
        } finally {
          reader.dispose();
        }
      }
    } catch (Throwable invalid) {
      System.exit(2);
    }
  }

  private static void require(boolean value) {
    if (!value) throw new IllegalArgumentException("INVALID_IMAGE");
  }

  static void validateContainer(byte[] b, String mime) {
    require(b.length >= 12);
    if ("image/png".equals(mime)) {
      require(
          java.util.Arrays.equals(
              java.util.Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}));
      int at = 8;
      boolean end = false;
      boolean first = true;
      while (at < b.length) {
        require(b.length - at >= 12);
        int len = ByteBuffer.wrap(b, at, 4).getInt();
        require(len >= 0 && (long) at + 12 + len <= b.length);
        String type = new String(b, at + 4, 4, StandardCharsets.US_ASCII);
        require(!type.equals("acTL") && !type.equals("fcTL") && !type.equals("fdAT"));
        if (first) require(type.equals("IHDR") && len == 13);
        first = false;
        var crc = new java.util.zip.CRC32();
        crc.update(b, at + 4, len + 4);
        require((int) crc.getValue() == ByteBuffer.wrap(b, at + 8 + len, 4).getInt());
        at += len + 12;
        if (type.equals("IEND")) {
          require(len == 0 && at == b.length);
          end = true;
          break;
        }
      }
      require(end);
    } else if ("image/webp".equals(mime)) {
      require(
          new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
              && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP"));
      long length =
          Integer.toUnsignedLong(ByteBuffer.wrap(b, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
      require(length + 8 == b.length);
      int at = 12;
      boolean pixels = false;
      while (at < b.length) {
        require(b.length - at >= 8);
        String type = new String(b, at, 4, StandardCharsets.US_ASCII);
        long len =
            Integer.toUnsignedLong(
                ByteBuffer.wrap(b, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        require(len <= b.length - at - 8);
        require(!type.equals("ANIM") && !type.equals("ANMF"));
        if (type.equals("VP8X")) require(len == 10 && (b[at + 8] & 2) == 0);
        if (type.equals("VP8 ") || type.equals("VP8L")) {
          require(!pixels);
          pixels = true;
        }
        at += (int) (8 + len + (len & 1));
      }
      require(pixels && at == b.length);
    } else if ("image/jpeg".equals(mime)) {
      require((b[0] & 255) == 255 && (b[1] & 255) == 216);
      int at = 2;
      boolean end = false;
      while (at < b.length) {
        require((b[at++] & 255) == 255);
        while (at < b.length && (b[at] & 255) == 255) at++;
        require(at < b.length);
        int marker = b[at++] & 255;
        if (marker == 217) {
          require(at == b.length);
          end = true;
          break;
        }
        require(marker != 0 && marker != 216 && marker != 1);
        require(at + 2 <= b.length);
        int len = ((b[at] & 255) << 8) | (b[at + 1] & 255);
        require(len >= 2 && at + len <= b.length);
        at += len;
        if (marker == 218) {
          while (at < b.length) {
            if ((b[at] & 255) != 255) {
              at++;
              continue;
            }
            require(at + 1 < b.length);
            int next = b[at + 1] & 255;
            if (next == 0 || next >= 208 && next <= 215) {
              at += 2;
              continue;
            }
            break;
          }
        }
      }
      require(end);
    } else throw new IllegalArgumentException("UNSUPPORTED_IMAGE");
  }
}
