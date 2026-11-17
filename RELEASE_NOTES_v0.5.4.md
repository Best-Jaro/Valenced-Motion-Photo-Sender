# Motion Photo Sender v0.5.4

Two alternative builds are available. **Install one edition at a time.**

## Downloads

- **MotionPhotoSender-Full-v0.5.4.zip** — Full edition: MP4 (original / freeze-frame / photo-cover), animated GIF, and still photo.
- **MotionPhotoSender-GIF-Only-v0.5.4.zip** — Smaller GIF-only edition. Tap the green Motion Photo badge to enable or disable GIF conversion.

Both ZIP files are directly installable in Valenced.

## What's new

- **Automatic temporary file cleanup:** Generated MP4 files in `Movies/MotionPhotoSender` and private conversion cache files are pruned after a five-minute upload grace period, with recovery cleanup on the next launch. Previous leftover exports are cleaned too.
- **English throughout:** Plugin dialogs, errors, status messages, and captions are now English.
- **Material 3-inspired format picker:** The Full edition replaces the old native list dialog with a modern bottom sheet, rounded option rows, format descriptions, dark mode, and Android 12+ dynamic color support where available.
- **Existing media conversion retained:** The original MP4/GIF processing logic is unchanged.

## Important notes

The Full and GIF Only ZIPs intentionally share the ID `dev.motion.photo.sender`. Switching editions replaces the plugin; do not run both side-by-side.

Temporary MP4 copies remain on the device for up to five minutes so Discord can finish uploading. If Discord is force-stopped or Android terminates the process, abandoned copies are removed on the next plugin start once sufficiently old. Cleanup is best-effort and does not erase original photos.

The build is compiled by GitHub Actions, but UI and lifecycle behavior should still be verified on a real Android device.
