# ScreenShield

Personal Android app that covers exposed nudity on screen in real time, fully on-device.

- Screen capture via MediaProjection → small in-memory ImageReader (never saved)
- NudeNet v3 `320n` detector (ONNX Runtime, XNNPACK), downloaded during the CI build
- Covers exposed genitals, female breasts, buttocks, anus (swimwear/"covered" classes are ignored)
- Black or blurred overlay boxes, enlarged and held to hide detector lag
- Own boxes in the capture: kept while the surroundings are static; otherwise briefly lifted (~60 ms) to re-check
- Quick-settings tile, checks-per-second setting, no INTERNET permission (enforced in CI)

## Install
Every push to `main` builds a signed APK and publishes it under **Releases**. Open the latest release in your phone's browser, download the `.apk`, install.

Model: NudeNet by notAI-tech (AGPL-3.0).
