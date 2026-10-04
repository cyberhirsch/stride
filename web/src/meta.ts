import exifr from "exifr";

/** Capture metadata extracted from a file, optionally filled from live browser sensors. */
export interface PhotoMeta {
  lat?: number;
  lon?: number;
  gps_accuracy?: number;
  altitude?: number;
  heading?: number;
  heading_accuracy?: number;
  pitch?: number;
  roll?: number;
  focal_length_mm?: number;
  focal_length_35mm?: number;
  fov_h?: number;
  fov_v?: number;
  width: number;
  height: number;
  captured_at: Date;
  device?: string;
  sources: Record<string, string>;
}

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : undefined);

export async function readMeta(file: File): Promise<PhotoMeta> {
  const [tags, dims] = await Promise.all([
    exifr.parse(file, { tiff: true, exif: true, gps: true, xmp: true, mergeOutput: true }).catch(() => null),
    imageSize(file),
  ]);
  const t = (tags ?? {}) as Record<string, any>;
  const xmp = (t.stride ?? t) as Record<string, any>;

  const meta: PhotoMeta = {
    ...dims,
    captured_at: t.DateTimeOriginal instanceof Date ? t.DateTimeOriginal : new Date(file.lastModified),
    sources: {},
  };
  if (num(t.latitude) !== undefined && num(t.longitude) !== undefined) {
    meta.lat = t.latitude;
    meta.lon = t.longitude;
    meta.sources.position = "exif";
  }
  meta.gps_accuracy = num(t.GPSHPositioningError);
  meta.altitude = num(t.GPSAltitude) !== undefined ? (t.GPSAltitudeRef === 1 ? -t.GPSAltitude : t.GPSAltitude) : undefined;

  if (num(t.GPSImgDirection) !== undefined) {
    meta.heading = ((t.GPSImgDirection % 360) + 360) % 360;
    meta.sources.heading = t.GPSImgDirectionRef === "M" ? "exif-magnetic" : "exif-true";
  }
  meta.heading_accuracy = num(xmp.HeadingAccuracy);
  if (num(xmp.Pitch) !== undefined && num(xmp.Roll) !== undefined) {
    meta.pitch = xmp.Pitch;
    meta.roll = xmp.Roll;
    meta.sources.tilt = "xmp";
  }

  meta.focal_length_mm = num(t.FocalLength);
  meta.focal_length_35mm = num(t.FocalLengthIn35mmFormat) ?? num(t.FocalLengthIn35mmFilm);
  const fov = fovFrom35mm(meta.focal_length_35mm, meta.width, meta.height);
  meta.fov_h = num(xmp.FovH) ?? fov?.h;
  meta.fov_v = num(xmp.FovV) ?? fov?.v;
  meta.device = [t.Make, t.Model].filter(Boolean).join(" ").trim() || undefined;
  return meta;
}

/** Field of view of an image from its 35 mm equivalent focal length (defined on the 43.27 mm diagonal). */
export function fovFrom35mm(f35: number | undefined, w: number, h: number) {
  if (!f35 || !w || !h) return undefined;
  const halfDiag = Math.atan(43.27 / (2 * f35));
  const d = Math.hypot(w, h);
  const deg = (r: number) => (r * 180) / Math.PI;
  return {
    h: deg(2 * Math.atan((Math.tan(halfDiag) * w) / d)),
    v: deg(2 * Math.atan((Math.tan(halfDiag) * h) / d)),
  };
}

async function imageSize(file: File): Promise<{ width: number; height: number }> {
  // browsers apply the EXIF orientation when decoding, so these are display dimensions
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return { width: img.naturalWidth, height: img.naturalHeight };
  } finally {
    URL.revokeObjectURL(url);
  }
}

/* ---------- live browser sensors, fallback for camera captures without EXIF ---------- */

export interface LiveSample {
  lat?: number;
  lon?: number;
  gps_accuracy?: number;
  altitude?: number;
  heading?: number;
  heading_source?: string;
}

/**
 * Watches geolocation and device orientation while the browser camera is open.
 * Sensor values are a fallback only: iOS Safari strips GPS from picked photos,
 * and the heading sampled on return is the phone's back direction, not
 * necessarily the shot's. Pitch is not taken from here for the same reason.
 */
export class LiveSensors {
  sample: LiveSample = {};
  private watchId?: number;
  private onOrient = (e: DeviceOrientationEvent) => {
    const ios = (e as any).webkitCompassHeading;
    if (typeof ios === "number" && ios >= 0) {
      this.sample.heading = ios;
      this.sample.heading_source = "web-webkitCompassHeading";
    } else if (e.absolute && e.alpha !== null && e.beta !== null && e.gamma !== null) {
      this.sample.heading = backCameraHeading(e.alpha, e.beta, e.gamma);
      this.sample.heading_source = "web-deviceorientationabsolute";
    }
  };

  /** Call from a user gesture (iOS asks for motion permission). */
  async start() {
    const DOE = DeviceOrientationEvent as any;
    if (typeof DOE?.requestPermission === "function") {
      await DOE.requestPermission().catch(() => "denied");
    }
    window.addEventListener("deviceorientationabsolute" as any, this.onOrient as any);
    window.addEventListener("deviceorientation", this.onOrient);
    if ("geolocation" in navigator) {
      this.watchId = navigator.geolocation.watchPosition(
        (p) => {
          this.sample.lat = p.coords.latitude;
          this.sample.lon = p.coords.longitude;
          this.sample.gps_accuracy = p.coords.accuracy;
          this.sample.altitude = p.coords.altitude ?? undefined;
        },
        () => {},
        { enableHighAccuracy: true, maximumAge: 5000 },
      );
    }
  }

  stop() {
    window.removeEventListener("deviceorientationabsolute" as any, this.onOrient as any);
    window.removeEventListener("deviceorientation", this.onOrient);
    if (this.watchId !== undefined) navigator.geolocation.clearWatch(this.watchId);
  }
}

/** Compass heading of the device's −Z axis (back camera), per the W3C DeviceOrientation spec example. */
export function backCameraHeading(alpha: number, beta: number, gamma: number) {
  const r = Math.PI / 180;
  const cA = Math.cos(alpha * r), sA = Math.sin(alpha * r);
  const sB = Math.sin(beta * r);
  const cG = Math.cos(gamma * r), sG = Math.sin(gamma * r);
  const vx = -cA * sG - sA * sB * cG;
  const vy = -sA * sG + cA * sB * cG;
  return ((Math.atan2(vx, vy) * 180) / Math.PI + 360) % 360;
}
