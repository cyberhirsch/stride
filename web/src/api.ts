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
