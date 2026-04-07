# GM Infotainment Video System Documentation

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 7, 2025

---

## Document Index

| Document | Description |
|----------|-------------|
| [video_codecs.md](video_codecs.md) | Complete video codec specifications (H.264, H.265, VP8, VP9, AV1, etc.) |
| [hardware_rendering.md](hardware_rendering.md) | GPU, OpenGL, Vulkan, and hardware rendering pipeline |
| [software_rendering.md](software_rendering.md) | CPU-based codec and rendering specifications |
| [display_subsystem.md](display_subsystem.md) | Display panel, SurfaceFlinger, and composition details |

---

## Quick Reference

### Hardware Summary

| Component | Specification |
|-----------|---------------|
| CPU | Intel IoT CPU 1.0, 4 cores @ 1.88 GHz |
| GPU | Intel HD Graphics 505 (Apollo Lake) |
| Display | 2400x960 @ 60Hz (DD134IA-01B) |
| OpenGL ES | 3.2 (Mesa 21.1.5) |
| Vulkan | 1.0.64 (Broxton driver) |

### Video Capabilities at a Glance

| Capability | Hardware | Software |
|------------|----------|----------|
| H.264 Decode | 4K60 @ 40Mbps | 4K @ 48Mbps |
| H.265 Decode | 4K60 @ 40Mbps | 4K @ 10Mbps |
| VP8 Decode | 4K60 @ 40Mbps | 2K @ 40Mbps |
| VP9 Decode | 4K60 @ 40Mbps | 2K @ 40Mbps |
| AV1 Decode | Not Available | 2K @ 120Mbps |
| H.264 Encode | 4K60 @ 40Mbps | 2K @ 12Mbps |
| H.265 Encode | 4K60 @ 40Mbps | 512p @ 4Mbps |
| DRM/Secure | H.264, H.265 | N/A |

### Optimal CarPlay/Android Auto Settings

```
Codec: H.264 (video/avc)
Decoder: OMX.Intel.hw_vd.h264
Resolution: 1920x1080 or 1280x720
Frame Rate: 30-60 fps
Bitrate: 5-15 Mbps
Profile: Baseline or Main
Color: NV12 (YUV420SemiPlanar)
```

---

## Key Findings

### Strengths
- Full 4K60 hardware decode/encode for H.264, H.265, VP8, VP9
- Dedicated Intel VPU for video processing
- DRM secure playback support
- Triple-buffered display for smooth rendering
- OpenGL ES 3.2 with geometry/tessellation shaders

### Limitations
- No AV1 hardware decode (software only)
- No HDR10/HLG/Dolby Vision support
- No wide color gamut display
- Single 60Hz display mode (no VRR)
- Software decode at 1080p achieves only 11-32 fps

### Recommendations
1. Always use hardware codecs (`OMX.Intel.*`) for real-time playback
2. Prefer H.264 for maximum compatibility
3. Use SurfaceView for video to enable HWC overlay
4. Target 1080p maximum for optimal performance
5. Use NV12 color format for hardware decode path

---

## System Properties Reference

```properties
ro.board.platform=broxton
ro.hardware.gralloc=broxton
ro.hardware.hwcomposer=broxton
ro.hardware.vulkan=broxton
ro.opengles.version=196610
ro.hardware.type=automotive
```

---

## Data Sources

All specifications obtained via ADB from connected device:
- `dumpsys SurfaceFlinger`
- `dumpsys display`
- `dumpsys media.player`
- `dumpsys gpu`
- `/system/etc/media_codecs.xml`
- `/system/etc/media_profiles_V1_0.xml`
- System properties (`getprop`)
- Package manager features (`pm list features`)
