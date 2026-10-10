package com.flyvo.socialcore.media.web;

import com.flyvo.socialcore.media.application.Images;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media")
class ImageController {
  private final Images images;

  ImageController(Images images) {
    this.images = images;
  }

  public record Finalize(String versionId) {}

  @PostMapping("/uploads")
  ResponseEntity<Images.UploadIntent> upload(
      @AuthenticationPrincipal ActorContext actor,
      @RequestBody Images.UploadRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    var r = images.upload(actor, request, key);
    return ResponseEntity.created(URI.create("/api/v1/media/" + r.id()))
        .header("Cache-Control", "private, no-store")
        .body(r);
  }

  @PostMapping("/{id}/finalize")
  ResponseEntity<Images.Status> finalizeUpload(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestBody Finalize request) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/media/" + id))
        .body(images.finalizeUpload(actor, id, request.versionId()));
  }

  @GetMapping("/{id}")
  Images.Status status(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    return images.status(actor, id);
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    images.delete(actor, id);
    return ResponseEntity.noContent().build();
  }
}
