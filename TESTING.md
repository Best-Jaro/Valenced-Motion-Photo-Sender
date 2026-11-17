# Testing checklist

1. Install the compiled ZIP in Valenced, restart Discord.
2. Select a JPEG Motion Photo originally captured by OnePlus (do not share the personal sample in public GitHub).
3. Verify the format prompt offers MP4, GIF, photo and cancel.
4. Check that ordinary JPEGs upload unchanged.
5. MP4 should have a 0.5 s still intro and retain audio; Discord thumbnail selection is not guaranteed.
6. GIF should have the photo as its first frame.
7. Test fallback and multiple attachments; report device/Discord version and logs, without sharing private photos.

**Experimental:** JS attachment interception and Android MediaCodec need real-device validation.

## v0.4.0 regression checks

- Source OnePlus HEVC: coded 1440x1080, display rotation -90°. Fast MP4 must retain the original stream unchanged and play vertically.
- Cover MP4 must display portrait as portrait (black bars may appear on unusual source ratios), first 0.5s must show photo.
- GIF colors: compare sky/car/body to original; gradients should have substantially less color shifting than fixed RGB332.
- Test fast MP4, cover MP4, GIF separately; the fast version has NO embedded photo intro.
- Time processing separately for extraction, cover encoding and GIF; do not promise timing on all phones.
- Android share still requires confirming the share in Discord, not the original gallery Send button.

## v0.5.0 regression checks

- Choose MP4 using the small green button on a Motion Photo thumbnail, **select that thumbnail normally**, then press Discord's original gallery Send. No Android Share Sheet should open. MP4 should be attached instead of JPEG.
- Other attachments should be left unchanged.
- Fast freeze MP4 should show the first VIDEO frame for about 0.5s without re-encoding; verify sound, orientation, frame rate and thumbnail.
- The separate high-resolution JPEG photo intro remains in the slow H.264 mode.
- If no original Motion Photo data is available, uploading the JPEG instead should be blocked with a visible error.

## v0.5.1 MP4 upload tests

1. Choose fast original MP4 and use Discord gallery Send. Expected: video attachment, not JPEG, no sharing dialog, no Upload Failed.
2. Test fast freeze MP4, then full cover MP4. Confirm first two maintain source FPS and quality.
3. Confirm that GIF still works; it uses the previous descriptor path.
4. If video still fails, collect Valenced logcat/diagnostics around CloudUpload and actual upload failure; do not assume v0.5.1 has fixed device-specific issues.
