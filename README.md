# Motion Photo Sender for Valenced

Convert Android Motion Photos into animated GIFs or MP4 videos directly in Discord's media picker, without an Android share sheet.

This repository contains **two alternative editions** of the same Valenced plugin. Choose only one at a time.

## Editions

### Full — MP4, GIF, still photo

The Full edition supports:
- **Original video:** extract the embedded MP4 without re-encoding (fast, preserves original video/audio).
- **Freeze frame:** prepend a 0.5-second hold of the first video keyframe without re-encoding the remaining video.
- **Photo cover:** prepend the original high-resolution still photo for 0.5 seconds (slower and requires re-encoding).
- **Animated GIF:** convert to a GIF with an adaptive per-frame color palette.
- **Still photo:** send the image without animation.

Tap the green badge on a Motion Photo thumbnail to open a **Material 3-inspired bottom sheet**. Choose the format, select the thumbnail, then use Discord's ordinary Send button. The converted media is added to the same channel/draft.

**Note:** the MP4 upload path is experimental across Discord versions and Android manufacturers. The original video and freeze-frame modes preserve the underlying HEVC/AAC stream; the photo-cover mode re-encodes video.

### GIF Only — smaller and simpler

The GIF Only edition includes the Motion Photo parser and GIF encoder, but no MP4 rendering or video export features. Tap the green badge on a Motion Photo thumbnail to enable GIF; tap again to disable it. Select the thumbnail and send normally in Discord.

Both ZIPs intentionally use the **same plugin ID** (`dev.motion.photo.sender`). Installing one edition replaces the other. Disable or remove the previous edition and restart Discord after switching.

## English interface

All plugin prompts, menus, notifications, and error messages are in English. The Full edition uses a rounded, Material 3-inspired native Android bottom sheet with dark mode and optional Android 12+ dynamic colors.

## Temporary file cleanup

The plugin converts media locally on your device; it never uploads data to third-party conversion services.

Discord's Android MP4 uploader requires a valid `content://media/...` URI. The Full edition therefore temporarily publishes a generated video in Android MediaStore (`Movies/MotionPhotoSender`). It **automatically deletes its own generated media and cache files after a five-minute grace period** while the app is running. Old generated files from previous sessions are also cleaned at startup. This includes leftovers from older editions that accumulated in the dedicated folder.

The five-minute window protects in-progress uploads and retries. This is **best-effort cleanup**, not an absolute guarantee of immediate deletion: if the app is force-stopped or the device reboots, cleanup happens at the next plugin launch after files become stale. Avoid manually renaming unrelated files into the dedicated `MotionPhotoSender` folder.

The GIF Only edition stores generated files only in the app's private cache and uses the same cleanup policy.

## Download and install

**[GitHub Releases](https://github.com/Best-Jaro/Valenced-Motion-Photo-Sender/releases)**

Download either `MotionPhotoSender-Full-v0.5.4.zip` or `MotionPhotoSender-GIF-Only-v0.5.4.zip`. They are **directly installable Valenced plugin ZIPs**: each contains `manifest.json`, `index.js`, and `plugin.jar`.

Install through Valenced's plugin settings, then restart Discord.

## Building from source

The official [Valenced plugin template](https://github.com/Valenced/valenced-plugin-template) and a GitHub Actions workflow are used to build both editions. The workflow compiles the Android Kotlin implementation and TypeScript bundle separately, verifies both archives, and publishes them as distinct assets in one GitHub Release.

Native Kotlin sources:
- `plugins/motion-photo-sender/src/main/kotlin/dev/motion/photo`
- `plugins/motion-photo-gif-only/src/main/kotlin/dev/motion/photo`

## Limitations

- The plugin requires access to the original Motion Photo, not an image already stripped of embedded video.
- Motion Photo metadata varies by manufacturer. Pixel, OnePlus/OPPO, Samsung, Xiaomi, and other variants may require additional device-specific testing.
- GIFs do not preserve audio and are limited to 256 colors per frame.
- Discord chooses video thumbnails independently; placing a still frame at the beginning does **not** guarantee its thumbnail.
- Never share private photos or raw logs containing authentication tokens when reporting issues.
