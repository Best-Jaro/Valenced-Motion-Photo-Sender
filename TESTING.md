# Testing checklist

1. Install the compiled ZIP in Valenced, restart Discord.
2. Select a JPEG Motion Photo originally captured by OnePlus (do not share the personal sample in public GitHub).
3. Verify the format prompt offers MP4, GIF, photo and cancel.
4. Check that ordinary JPEGs upload unchanged.
5. MP4 should have a 0.5 s still intro and retain audio; Discord thumbnail selection is not guaranteed.
6. GIF should have the photo as its first frame.
7. Test fallback and multiple attachments; report device/Discord version and logs, without sharing private photos.

**Experimental:** JS attachment interception and Android MediaCodec need real-device validation.
