# Motion Photo Sender — manual test checklist

## Installation
- Install the Full edition and restart Discord.
- Switch to GIF Only; verify it replaces the Full edition rather than running both.
- Confirm the green Motion Photo indicator still appears on supported photo thumbnails.

## Full edition
- Open the format picker and verify the new Material 3-inspired bottom sheet on light and dark mode.
- Check all menu headings, subtitles, error messages, notifications, and button labels are in English.
- Send Original MP4, Freeze-frame MP4, Photo-cover MP4, GIF, and a still photo in the current Discord channel.
- Verify the Android share sheet is not launched.
- Verify MP4 audio, orientation, framerate, and original-quality extraction.
- Confirm no unconverted original JPEG is sent when a video conversion fails.

## GIF Only
- Tap green badge to enable GIF and again to disable. Confirm the status notifications are English.
- Send the selected Motion Photo as GIF in Discord's current chat.
- Confirm no MP4 selection or encoding functionality appears.

## Cleanup
- Confirm converted MP4 is temporarily accessible via Android MediaStore (needed by Discord's video uploader).
- Wait more than five minutes with Discord running; newly generated videos under Movies/MotionPhotoSender should be deleted.
- Confirm generated JPG/GIF/MP4 files in the plugin's cache are cleaned after the grace period.
- Restart Discord after a previous version has accumulated MotionPhotoSender exports. Old files in the dedicated generated folder should be removed once older than five minutes.
- Verify original images in DCIM/Camera remain untouched.
- Force-stop Discord after conversion, restart later, and verify startup pruning clears abandoned generated files.
- Test multiple simultaneous Motion Photos; no in-progress upload should be broken by cleanup.

The five-minute cleanup is a compromise to support slow uploads. Files can remain temporarily, and force-stop recovery requires another plugin launch.
