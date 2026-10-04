import PocketBase, { type RecordModel } from "pocketbase";

const DEFAULT_URL = "http://192.168.178.66:8091";

export const pb = new PocketBase(
  localStorage.getItem("stride.api") || import.meta.env.VITE_PB_URL || DEFAULT_URL,
);
pb.autoCancellation(false);

export type License = "arr" | "cc-by" | "cc-by-sa" | "cc0";

export const LICENSES: Record<License, string> = {
  "cc-by": "CC BY 4.0",
  "cc-by-sa": "CC BY-SA 4.0",
  cc0: "CC0 (public domain)",
  arr: "All rights reserved",
};

export interface Photo extends RecordModel {
  image: string;
  title: string;
  license: License;
  platform: "ios" | "android" | "web";
  device: string;
  captured_at: string;
  lat: number;
  lon: number;
  gps_accuracy: number;
  altitude: number;
  altitude_accuracy: number;
  heading: number;
  heading_accuracy: number;
  pitch: number;
  roll: number;
  has_heading: boolean;
  has_tilt: boolean;
  focal_length_mm: number;
  focal_length_35mm: number;
  fov_h: number;
  fov_v: number;
  width: number;
  height: number;
  author: string;
  expand?: { author?: RecordModel };
}

export const MAP_FIELDS =
  "id,collectionId,collectionName,image,lat,lon,heading,has_heading,fov_h,captured_at,title";

export function imageUrl(p: Pick<Photo, "id" | "collectionId" | "collectionName" | "image">, thumb?: string) {
  return pb.files.getURL(p as RecordModel, p.image, thumb ? { thumb } : undefined);
}

export async function photosInBounds(s: number, w: number, n: number, e: number) {
  // the map can span the antimeridian; split into two boxes then
  const lonFilter =
    w <= e ? pb.filter("lon >= {:w} && lon <= {:e}", { w, e }) : pb.filter("(lon >= {:w} || lon <= {:e})", { w, e });
  const filter = `${pb.filter("lat >= {:s} && lat <= {:n}", { s, n })} && ${lonFilter}`;
  const res = await pb.collection("photos").getList<Photo>(1, 500, {
    filter,
    sort: "-captured_at",
    fields: MAP_FIELDS,
    skipTotal: true,
  });
  return res.items;
}

export function getPhoto(id: string) {
  return pb.collection("photos").getOne<Photo>(id, { expand: "author" });
}

export function currentUser() {
  return pb.authStore.isValid ? pb.authStore.record : null;
}

/* ---------- alignment results ---------- */

export interface Place extends RecordModel {
  status: "pending" | "queued" | "aligned" | "failed";
  lat: number;
  lon: number;
  photo_count: number;
  ok_count: number;
  origin_lat: number;
  origin_lon: number;
  origin_alt: number;
  points: string;
  points_count: number;
}

/** Camera pose in the place's east-north-up frame; see docs/API.md. */
export interface Pose extends RecordModel {
  place: string;
  photo: string;
  ok: boolean;
  x: number;
  y: number;
  z: number;
  qx: number;
  qy: number;
  qz: number;
  qw: number;
  fx: number;
  fy: number;
  cx: number;
  cy: number;
  median_depth: number;
  heading: number;
  expand?: { photo?: Photo };
}

export function getPlace(id: string) {
  return pb.collection("places").getOne<Place>(id);
}

export function placePoses(placeId: string) {
  return pb.collection("poses").getFullList<Pose>({
    filter: pb.filter("place = {:p} && ok = true", { p: placeId }),
    expand: "photo",
    fields: "*,expand.photo.id,expand.photo.collectionId,expand.photo.collectionName,expand.photo.image,expand.photo.width,expand.photo.height,expand.photo.title",
  });
}

export async function poseOfPhoto(photoId: string): Promise<Pose | null> {
  const r = await pb.collection("poses").getList<Pose>(1, 1, { filter: pb.filter("photo = {:p} && ok = true", { p: photoId }) });
  return r.items[0] ?? null;
}

export async function alignedPlacesInBounds(s: number, w: number, n: number, e: number) {
  const res = await pb.collection("places").getList<Place>(1, 200, {
    filter: pb.filter("status = 'aligned' && ok_count >= 2 && lat >= {:s} && lat <= {:n} && lon >= {:w} && lon <= {:e}", { s, n, w, e }),
    fields: "id,lat,lon,ok_count,photo_count",
    skipTotal: true,
  });
  return res.items;
}

export function pointsUrl(place: Place) {
  return pb.files.getURL(place, place.points);
}
