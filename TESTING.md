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
