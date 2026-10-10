package com.flyvo.socialcore.media.application.port;

import java.util.List;

public interface ImageProcessor {
  record Image(String name, String mime, byte[] bytes, int width, int height) {}

  List<Image> process(byte[] input, String declaredMime);
}
