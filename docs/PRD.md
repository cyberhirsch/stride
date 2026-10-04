# Stride: Product Requirements

Status: draft · Owner: Seb Hirsch · Last updated: 2026-10-04

## 1. Summary

Stride is a world map of geotagged photos. Where several photos show the same
place, Stride aligns them in 3D, and you can walk from one photo to the next
in the direction you choose. A time slider shows how a place changed over the
years. A capture app records position, heading and tilt with every shot and
guides people to the angles that are still missing.

It brings back what Panoramio and Photosynth did well, separately, and adds
the time axis neither had.

## 2. Problem

- Panoramio (closed 2016) had a loved community and a world map of photos,
  but each photo stood alone.
- Photosynth (closed 2017) aligned photos into explorable 3D scenes, but
  scenes were isolated, uploaded one by one, and not on a shared map.
- Street-level services (Google Street View, Mapillary) cover roads with
  systematic capture but are weak on the places people actually photograph:
  squares, viewpoints, trails, courtyards, ruins.
- Nobody lets you see the same spot across years from many photographers'
  viewpoints.

## 3. Goals

1. **Explore by walking:** move through a place photo by photo, in any
   direction, with smooth transitions.
2. **One shared map:** every aligned place is part of one world map, not a
   separate upload.
3. **Better data at capture:** sensor metadata makes alignment cheaper and
   more reliable than photos alone.
4. **Coverage grows on purpose:** the app shows gaps and guides users to fill
   them.
5. **Time:** the same spot across years (v2).
6. **Community:** profiles, likes, comments, groups (v2).

## 4. Non-goals (MVP)

- No full 3D reconstruction or mesh export for users. Alignment serves
  navigation.
- No indoor private spaces.
- No video.
- No worldwide launch: one city or region first.

## 5. Users

| user | needs |
|---|---|
| **Explorers** | browse places from home, walk through them |
| **Photographers** | share photos with context; see their shot placed among others |
| **Travellers** | preview a place from real eye-level viewpoints |
| **Local historians** | collect then-and-now photos of one spot (v2) |
| **Contributors** | a reason to shoot: gaps to fill, recognition for it |

## 6. Core concepts

| term | meaning |
|---|---|
| **Photo** | an image with GPS position, compass heading, tilt (pitch, roll), field of view, capture time and author |
| **Pose** | the photo's position and orientation in 3D after alignment, with an accuracy estimate |
| **Place** | a cluster of photos that share visual features and are aligned in one 3D space |
| **Explorable place** | a place with enough aligned photos to walk through (threshold to be set, e.g. ≥ 10 photos, ≥ 2 directions) |
| **Gap** | a position or viewing direction inside a place with no photo |
| **Era** | a time bucket for the time slider (v2) |

## 7. Requirements

Priorities: **P0** MVP, **P1** soon after, **P2** v2.

### 7.1 Map

| id | requirement | prio |
|---|---|---|
| M1 | World map with photo pins, clustered at low zoom | P0 |
| M2 | Explorable places marked distinctly from single photos | P0 |
| M3 | Heading cone on each pin showing which way the photo looks | P1 |
| M4 | Filter by date, author, explorable only | P1 |
| M5 | Shareable URL for a place, photo and view direction | P0 |

### 7.2 Capture app

| id | requirement | prio |
|---|---|---|
| C1 | Record GPS position and accuracy, compass heading, pitch, roll, focal length and timestamp with every shot | P0 |
| C2 | Store sensor data in the photo (EXIF/XMP) and send it alongside the upload | P0 |
| C3 | Compass calibration prompt when heading accuracy is poor | P1 |
| C4 | Live overlay of nearby existing photos and their directions | P1 |
| C5 | "Fill the gaps" mode: arrows and markers guide the user to missing positions and angles | P1 |
| C6 | Offline capture with later upload | P1 |

### 7.3 Upload

| id | requirement | prio |
|---|---|---|
| U1 | Upload from the capture app | P0 |
| U2 | Upload of existing photos with EXIF GPS from the web | P0 |
| U3 | Manual placement for photos without GPS (drag pin, set direction) | P1 |
| U4 | Licence choice per photo (all rights reserved, CC BY, CC BY-SA, CC0) | P0 |

### 7.4 Alignment

| id | requirement | prio |
|---|---|---|
| A1 | Candidate pairs from position, heading and capture-time proximity, so only plausible neighbours are matched | P0 |
| A2 | Feature matching and structure-from-motion on candidate clusters (e.g. COLMAP/GLOMAP, learned matchers such as SuperPoint + LightGlue) | P0 |
| A3 | Sensor data as pose priors to speed up and stabilise reconstruction | P0 |
| A4 | Incremental: new photos registered into an existing place without recomputing it | P1 |
| A5 | Georeference each place: fit the 3D model to GPS so it sits correctly on the map | P0 |
| A6 | Pose accuracy per photo; poorly aligned photos stay as pins, not in walk-through | P0 |
| A7 | Cross-era matching that survives seasonal and structural change (v2) | P2 |

### 7.5 Walk-through

| id | requirement | prio |
|---|---|---|
| W1 | Open a place at a photo; show arrows to neighbouring photos by direction (forward, back, left, right, turn) | P0 |
| W2 | Swipe or tap to move; smooth transition by projecting both photos onto the sparse 3D geometry | P0 |
| W3 | Zoom into a photo; move to the best photo for the zoomed area | P1 |
| W4 | Overview: see the place from above with all photo positions | P1 |
| W5 | Optional dense view (e.g. a Gaussian splat of the place) for smoother movement between photos | P2 |

### 7.6 Time (v2)

| id | requirement | prio |
|---|---|---|
| T1 | Time slider filtering photos of a place by capture date | P2 |
| T2 | Then-and-now: same viewpoint across years, side by side or with a slider | P2 |
| T3 | Historical photos: upload scans with an estimated date and manual alignment | P2 |

### 7.7 Community (v2)

| id | requirement | prio |
|---|---|---|
| S1 | Profiles with a contributor map | P2 |
| S2 | Likes, comments, follows | P2 |
| S3 | Groups (city, theme) | P2 |
| S4 | Recognition for filling gaps (badges, coverage stats) | P2 |

### 7.8 Privacy and moderation

| id | requirement | prio |
|---|---|---|
| P1 | Automatic blurring of faces and licence plates before publication | P0 |
| P2 | Original unblurred files never served publicly | P0 |
| P3 | Report button; removal on request (people, own property) | P0 |
| P4 | Automated screening for abusive content before publication | P0 |
| P5 | Strip precise capture metadata on request, e.g. photos taken near home | P1 |

## 8. Technical direction

Proposal, to be confirmed.

| layer | candidate | reason |
|---|---|---|
| capture app | native (Swift / Kotlin) or Flutter | reliable access to compass, gyro, camera intrinsics; a web app gets less precise heading |
| map | MapLibre GL | open, vector tiles, no per-load fee |
| alignment | COLMAP / GLOMAP + learned matchers, batch workers on GPU | proven open tools |
| walk-through viewer | WebGL (three.js), photos projected onto sparse geometry | runs in browser and in the app |
| storage | object storage for images, PostGIS for photos and poses | spatial queries for candidate pairs |
| privacy | face and plate detection model at upload | blur before anything is public |

## 9. MVP

- One city or region (candidate: Rosenheim or Munich).
- Capture app with sensor data; web upload with EXIF.
- Batch alignment of clusters; georeferenced places.
- Map plus walk-through view.
- Blurring, reporting, licence choice.
- **Out of MVP:** time slider, community, gap guidance (P1 right after).

## 10. Success metrics

- Share of uploaded photos that end up in an explorable place.
- Number of explorable places in the launch region.
- Median steps per walk-through session.
- Photos taken in "fill the gaps" mode, and how many close a gap.
- Alignment cost per photo (compute time, money).

## 11. Risks

| risk | mitigation |
|---|---|
| **Matching cost at scale** | sensor priors and spatial candidate pairs; incremental registration; batch on spot GPUs |
| **Too few photos per place** | launch in one region; seed with own capture sessions and student projects; gap guidance |
| **Moderation load** | automated screening, reporting, trusted reviewers |
| **Privacy law (GDPR)** | blur by default, removal on request, no public originals |
| **Freedom of panorama varies by country** | photos of public places are not equally free everywhere (e.g. recent buildings or artworks in some countries); handle takedowns, check per launch country |
| **Competition** | Mapillary and Street View own road imagery; Stride focuses on places people photograph and on time |
| **Name** | "Stride" is used by large companies; check trademark and domain, consider a suffix (see §13) |

## 12. Relation to Atlas Antiqua

Shared ideas: a map with a time axis, photogrammetry, and the same mobile
sensor capture. Stride could later feed aligned photo places of
archaeological sites into Atlas Antiqua. Kept as separate products.

## 13. Open questions

1. Launch region?
2. Native app or Flutter for capture?
3. Default photo licence?
4. Threshold for "explorable" (photos, directions, pose accuracy)?
5. Name: plain "Stride", or a suffix for trademark and app-store search?
6. Allow historical scans in v1 to seed the time slider early?
