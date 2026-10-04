# Stride API

Backend: a dedicated PocketBase (v0.40) on the Pi, container `stride-pb`.
Schema: [server/pb_migrations](../server/pb_migrations).

| where | base URL |
|---|---|
| LAN | `http://192.168.178.66:8091` |
| public | Cloudflare tunnel hostname (set per build, see each app's README) |

All clients use the standard PocketBase REST API.

## Auth

Collection `users` (email + password).

| action | request |
|---|---|
| register | `POST /api/collections/users/records` JSON `{email, password, passwordConfirm, name}` |
| login | `POST /api/collections/users/auth-with-password` JSON `{identity, password}` → `{token, record}` |
| refresh | `POST /api/collections/users/auth-refresh` with header |
| header | `Authorization: <token>` (no `Bearer` prefix needed) |

## Collection `photos`

| field | type | meaning |
|---|---|---|
| `author` | relation → users | must equal the logged-in user's id |
| `image` | file | JPEG (preferred), PNG or WebP, ≤ 30 MB; EXIF kept |
| `title` | text ≤ 200 | optional |
| `license` | `arr` · `cc-by` · `cc-by-sa` · `cc0` | required |
| `platform` | `ios` · `android` · `web` | required |
| `device` | text | e.g. `Google Pixel 8` |
| `captured_at` | date, ISO 8601 UTC | required |
| `lat`, `lon` | number, WGS84 degrees | required |
| `gps_accuracy` | metres, horizontal 1σ | |
| `altitude` | metres above WGS84 ellipsoid | |
| `altitude_accuracy` | metres | |
| `heading` | degrees 0–360, **true** north, clockwise | direction the camera's optical axis points |
| `heading_accuracy` | degrees | |
| `pitch` | degrees −90…90 | optical axis elevation; + = looking up, 0 = horizon |
| `roll` | degrees −180…180 | rotation about the optical axis; 0 = upright landscape-or-portrait as stored, + = clockwise as seen by the photographer |
| `focal_length_mm` | mm | physical focal length |
| `focal_length_35mm` | mm | 35 mm equivalent |
| `fov_h`, `fov_v` | degrees | field of view of the stored image (after rotation) |
| `width`, `height` | px | of the stored image |
| `has_heading` | bool | true if `heading` was measured; absent numbers are stored as 0 |
| `has_tilt` | bool | true if `pitch` and `roll` were measured |
| `sensors` | JSON | free-form extras: `{app_version, heading_source, magnetic_declination, calibration, samples: [...]}` |
| `hidden` | bool | set by moderation only; clients cannot write it |
| `created`, `updated` | autodate | |

Create: `POST /api/collections/photos/records`, `multipart/form-data`, one
part per field plus `image`. Rules: list/view public for `hidden = false`
(owners also see their own hidden ones); create needs auth; update and delete
only by the author.

Map query (bounding box):

```
GET /api/collections/photos/records
  ?filter=(lat>=S && lat<=N && lon>=W && lon<=E)
  &sort=-captured_at&perPage=500&skipTotal=1
  &fields=id,collectionId,image,lat,lon,heading,has_heading,fov_h,captured_at,title
```

Image URLs: `/api/files/photos/<id>/<image>`; thumbnails `?thumb=400x0` and
`?thumb=1600x0`.

## Collection `reports`

`POST /api/collections/reports/records` JSON `{photo, reason, note, reporter?}`;
`reason` ∈ `person` · `property` · `abuse` · `copyright` · `other`. Anyone can
report; `reporter` is optional and, if set, must be the caller. Three reports
hide a photo until a superuser reviews it.

## Metadata in the file

Besides the form fields, apps write the same data into the JPEG so the file
stands alone:

| EXIF | value |
|---|---|
| `GPSLatitude/Ref`, `GPSLongitude/Ref`, `GPSAltitude/Ref` | position |
| `GPSHPositioningError` | `gps_accuracy` |
| `GPSImgDirection` + `GPSImgDirectionRef = T` | `heading` (true) |
| `GPSTimeStamp`, `GPSDateStamp`, `DateTimeOriginal`, `OffsetTimeOriginal` | time |
| `FocalLength`, `FocalLengthIn35mmFilm` | optics |
| `Orientation` | 1 (pixels stored upright) preferred |

XMP namespace `https://stride.app/ns/1.0/` (prefix `stride`):
`stride:Pitch`, `stride:Roll`, `stride:HeadingAccuracy`, `stride:FovH`,
`stride:FovV`.
