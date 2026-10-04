# Stride for Android

Capture app for Stride: takes photos with position, true heading, pitch, roll and
lens data, writes them into the JPEG (EXIF + XMP) and uploads them to the Stride
PocketBase backend ([docs/API.md](../docs/API.md)). A map tab shows uploaded photos.

Kotlin, Jetpack Compose, CameraX, MapLibre. minSdk 26, compile/target SDK 36.

## Build

Requirements: JDK 17 and the Android SDK (platform 36). Point Gradle at the SDK with
`local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest   # orientation / optics / upload-field tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # minified; signed with the debug key unless a keystore is set
```

Release signing reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` from the environment. CI
(`.github/workflows/android.yml`) decodes the `ANDROID_KEYSTORE_BASE64` secret into
that file, uploads both APKs as artifacts and attaches the release APK to a GitHub
Release for `v*` tags.

## Server URL

The default backend is baked into `BuildConfig.STRIDE_API_URL`:

1. `-PstrideApiUrl=https://stride.example.org` on the Gradle command line, or
2. the `STRIDE_API_URL` environment variable (CI: repository variable `STRIDE_API_URL`),
3. otherwise the LAN server `http://192.168.178.66:8091`.

Users can change it at runtime in *Settings → Server* (also reachable from the login
screen). Plain `http` is only allowed for `192.168.178.66`, `localhost`, `127.0.0.1`
and `10.0.2.2` (emulator), see `app/src/main/res/xml/network_security_config.xml`;
any other server must use HTTPS.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or download `stride-*.apk` from the CI artifacts / GitHub Release and open it on the
phone (allow installing from unknown sources).

## How the metadata is measured

- **Position:** fused location, high accuracy, while the camera is open. The freshest
  fix at shutter time is used; its age goes into `sensors.location.fix_age_ms`.
  Altitude is the WGS84 ellipsoid height Android reports.
- **Orientation:** `TYPE_ROTATION_VECTOR`, averaged over the last 250 ms. Heading,
  pitch and roll describe the camera's optical axis (device −z) and are valid in
  portrait and landscape; the math is documented and unit-tested in
  `sensors/OrientationMath.kt`. Magnetic heading is converted to true north with
  `GeomagneticField`; the declination is stored in `sensors.magnetic_declination`.
  The HUD asks for a figure-8 calibration when the magnetometer reports low accuracy.
- **Optics:** focal length, sensor size and active array from Camera2; field of view
  and 35 mm equivalent are computed for the stored image size.
- **File:** pixels are stored upright (EXIF Orientation 1). GPS, direction, time and
  focal length go into EXIF; pitch, roll, heading accuracy and FOV into XMP
  (`https://stride.app/ns/1.0/`).

Captures are kept in app storage with a JSON sidecar and uploaded by WorkManager when
a network is available, so the camera works offline.
