# Stride for iOS

SwiftUI capture app for Stride: takes JPEG photos with precise position,
direction and tilt, writes the metadata into the file (EXIF/GPS/XMP) and
uploads to the Stride PocketBase backend ([docs/API.md](../docs/API.md)).
Also browses the photo map.

- iOS 17+, iPhone, portrait UI
- No third-party dependencies
- Project file is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) from `project.yml`

## Generate and run

```sh
brew install xcodegen
cd ios
xcodegen generate
open Stride.xcodeproj
```

Pick the `Stride` scheme, choose your iPhone, set your signing team under
*Signing & Capabilities* (a free personal team works for sideloading to your
own device), and run. The camera, compass and motion sensors only work on a
real device; the simulator is good for login, upload queue and map.

Tests (orientation math, upload fields):

```sh
xcodebuild test -project Stride.xcodeproj -scheme Stride \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

## Server URL

The backend URL is the `STRIDE_API_URL` build setting (default
`http://192.168.178.66:8091`, the Pi on the LAN), exposed to the app as the
Info.plist key `StrideAPIURL`. Change it in `project.yml` or per build:

```sh
xcodebuild ... STRIDE_API_URL=https://stride.example.org
```

At runtime, *Settings → Server* (also reachable from the login screen)
overrides it. Plain HTTP is allowed for the LAN address and local networks
only (App Transport Security); a public server must use HTTPS. After changing
servers, log out and in again.

## What gets recorded

| value | source |
|---|---|
| position, accuracy, altitude | CoreLocation, best accuracy; `altitude` = ellipsoidal (WGS84), EXIF `GPSAltitude` = above sea level |
| heading, pitch, roll | CoreMotion device motion in `xTrueNorthZVertical` (fallback `xMagneticNorthZVertical` + declination from CLHeading); camera optical axis = device −Z |
| heading accuracy | `CLHeading.headingAccuracy`; the HUD warns above 20° |
| field of view | active format `videoFieldOfView` + stored image size |
| focal length | EXIF of the captured photo |

`heading` is only sent (and `has_heading = true`) when it is relative to true
north. Raw extras (rotation matrix, gravity, last pose samples, calibration
state, fix age) go into the `sensors` JSON.

Photos are stored upright (EXIF Orientation 1) in Application Support with a
JSON sidecar and uploaded by a background `URLSession`, so capture works
offline. Pending uploads are retried on launch and when the app returns to
the foreground; rejected ones can be retried from the Uploads tab.

## Sideloading the CI build

The `iOS` GitHub Actions workflow builds and tests on macOS and uploads an
**unsigned** `Stride-unsigned.ipa`. It must be signed before it installs on a
device, e.g. with AltStore / SideStore or `codesign` plus a development
provisioning profile. Without an Apple Developer account, the simplest route
is building from Xcode with a free personal team (apps expire after 7 days).
Signing and TestFlight upload are stubbed out (commented) in
`.github/workflows/ios.yml`.
