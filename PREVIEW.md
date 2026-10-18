# Manga Romaji Preview — reversible comparison release

This is a separate preview of the Claude-created Manga Romaji app. Original baseline commit: `07339e6fb39cd3d64ca33cafc37c3485a6ec45b8`, preserved on `baseline/claude-build-5`.

## Install and reverse

The original package remains `com.example.mangaromaji`. This APK installs as **Manga Romaji Preview**, package `com.example.mangaromaji.preview`; it cannot update, overwrite, or clear the original app. Keep both installed. Stop the original's capture before starting the preview, and stop the preview before switching back. Uninstall only **Manga Romaji Preview** to remove these changes. Main is not merged or replaced. Preview APKs use one public development-only signing key so future preview updates can retain their own settings. Do not use this signing key for a production release.

## Changes

- Keeps ML Kit Japanese recognition and Claude's Kuromoji pronunciation rules, with a bounded 512-entry reading cache.
- One OCR pass per settled screen; no downloaded models, online API, tile fallback or per-region second OCR pass.
- Event-driven latest-frame capture on a dedicated thread, bulk RGBA row copies, bounded bitmap ownership and explicit frame-copy failures.
- Capture/motion observation continues while OCR runs. Page generations reject old results; an overlay compositor marker validates a fresh caption-free frame before publishing. Static captions are not periodically hidden.
- A single MediaProjection VirtualDisplay is resized rather than recreated. Entire-display capture is requested on Android 14. No FLAG_SECURE is set.
- Draggable RO button remembers position. Tap to pause/resume; hold and release to stop. Notification has Rescan and Stop.
- Caption size is in Android sp units with a readable floor, local wrapping and ellipsis; the full latest reading is available in Settings. Vertical columns are joined before tokenization for context across line breaks.
- Home has Start and Settings only. Settings includes caption size, full reading, diagnostics and Stop.
- Reports distinguish frames, compositor acknowledgement, OCR, zero Japanese regions, stale work and persistent failures. OCR and romaji timings are separated.

The tiny green/magenta marker is intentional: it acknowledges the actual overlay buffer present in captured frames. Android overlay window alpha is 0.78 to permit touch-through on supported devices. Very narrow/short bubbles can still require the full-reading view. ML Kit detection and dictionary readings can still be wrong; the preview does not claim to solve every reading error.

## Phone comparison

Use the same manga page and zoom for both apps, one active capture at a time. Compare fresh-page responsiveness, scrolling, screenshot availability, button dragging, caption readability and reading quality. Copy Preview Settings → Diagnostics after the test. Its single-pass timings measure this pipeline; compare against an actual original-app phone run before claiming one is faster. CI runs focused JVM tests, lint and APK assembly, without an emulator, model inference benchmark or paid API call.

## Release scope

This is a prerelease on a separate branch with a unique APK filename and visible build version. Original releases remain available. A successful build establishes packaging and automated checks, not real-device functionality or performance.
