package com.flyvo.socialcore.media.infrastructure.processing;

import com.flyvo.socialcore.media.application.port.ImageProcessor;
import com.flyvo.socialcore.platform.api.Failure;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class IsolatedImageProcessor implements ImageProcessor {
  public List<Image> process(byte[] input, String mime) {
    if (input == null || input.length == 0 || input.length > 10485760)
      throw new Failure(413, "IMAGE_TOO_LARGE");
    Process child = null;
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    try {
      var args =
          new java.util.ArrayList<String>(
              List.of(
                  System.getProperty("java.home") + "/bin/java",
                  "-Xmx384m",
                  "-XX:ActiveProcessorCount=1",
                  "-Djava.awt.headless=true",
                  "-cp",
                  System.getProperty("java.class.path")));
      // Packaged Boot jar uses its existing loader, with only this standalone main.
      if (System.getProperty("java.class.path").endsWith(".jar")
          && ImageProcessMain.class
              .getResource("ImageProcessMain.class")
              .getProtocol()
              .equals("jar")) {
        args.add(1, "-Dloader.main=" + ImageProcessMain.class.getName());
        args.add("org.springframework.boot.loader.launch.PropertiesLauncher");
      } else args.add(ImageProcessMain.class.getName());
      var builder = new ProcessBuilder(args);
      builder.environment().clear();
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      child = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      Process process = child;
      var output =
          executor.submit(
              () -> {
                try (var in = new DataInputStream(process.getInputStream())) {
                  var result = new java.util.ArrayList<Image>();
                  for (String name : List.of("DISPLAY", "THUMBNAIL")) {
                    int w = in.readInt(), h = in.readInt(), size = in.readInt();
                    if (w < 1 || h < 1 || w > 2048 || h > 2048 || size < 1 || size > 10485760)
                      throw new IllegalArgumentException();
                    byte[] b = in.readNBytes(size);
                    if (b.length != size) throw new IllegalArgumentException();
                    result.add(new Image(name, "image/png", b, w, h));
                  }
                  if (in.read() != -1) throw new IllegalArgumentException();
                  return List.copyOf(result);
                }
              });
      var sent =
          executor.submit(
              () -> {
                try (var out = new DataOutputStream(process.getOutputStream())) {
                  out.writeUTF(mime);
                  out.writeInt(input.length);
                  out.write(input);
                }
                return true;
              });
      sent.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      var result = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (!child.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
          || child.exitValue() != 0) throw new IllegalArgumentException();
      return result;
    } catch (Exception invalid) {
      throw new Failure(415, "INVALID_IMAGE");
    } finally {
      if (child != null) child.destroyForcibly();
      executor.shutdownNow();
    }
  }
}
