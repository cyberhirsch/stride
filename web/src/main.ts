import type maplibregl from "maplibre-gl";
import "./style.css";
import { currentUser, pb, type Photo } from "./api";
import { openAccount, openAuth } from "./auth";
import { PhotoMap } from "./map";
import { PhotoPanel } from "./panel";
import { toast } from "./ui";
import { Uploader } from "./upload";

// URL hash: #map=zoom/lat/lon and/or photo=<id>, joined with &
function readHash() {
  const params = new URLSearchParams(location.hash.slice(1));
  const [z, lat, lon] = (params.get("map") ?? "").split("/").map(Number);
  const view = Number.isFinite(lat) && Number.isFinite(lon) && z ? { zoom: z, center: [lon, lat] as [number, number] } : null;
  return { view, photo: params.get("photo") };
}

const initial = readHash();
// default view: Rosenheim, the candidate launch region
const photoMap = new PhotoMap("map", initial.view ?? { center: [12.1289, 47.8561], zoom: 13 });
const panel = new PhotoPanel();
let selected: string | null = null;

function writeHash() {
  const c = photoMap.map.getCenter();
  const parts = [`map=${photoMap.map.getZoom().toFixed(2)}/${c.lat.toFixed(5)}/${c.lng.toFixed(5)}`];
  if (selected) parts.push(`photo=${selected}`);
  history.replaceState(null, "", `#${parts.join("&")}`);
}

async function select(id: string | null, fly = false) {
  selected = id;
  writeHash();
  if (!id) {
    panel.hide();
    photoMap.whenReady(() => photoMap.select(null));
    photoMap.map.easeTo({ padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: 300 });
    return;
  }
  const p = await panel.show(id);
  if (!p) return;
  photoMap.whenReady(() => moveTo(p, fly));
}

function moveTo(p: Photo, fly: boolean) {
  photoMap.select(p);
  // keep the pin visible beside (desktop) or above (mobile) the panel
  const mobile = window.innerWidth <= 720;
  photoMap.map.easeTo({
    center: [p.lon, p.lat],
    zoom: fly ? Math.max(photoMap.map.getZoom(), 17) : photoMap.map.getZoom(),
    padding: mobile ? { top: 0, bottom: window.innerHeight * 0.62, left: 0, right: 0 } : { top: 0, bottom: 0, left: 0, right: 400 },
    duration: fly ? 0 : 400,
  });
}

photoMap.onSelect = (id) => select(id);
photoMap.onMove = writeHash;
panel.onClose = () => select(null);
panel.onDeleted = (id) => {
  photoMap.remove(id);
  select(null);
};

/* ---- picking a position on the map for photos without GPS ---- */
let pickResolve: ((v: maplibregl.LngLat | null) => void) | null = null;
const banner = document.createElement("div");
banner.className = "pick-banner";
banner.hidden = true;
document.body.append(banner);

function pickOnMap(prompt: string) {
  return new Promise<maplibregl.LngLat | null>((resolve) => {
    pickResolve = resolve;
    banner.replaceChildren(prompt, Object.assign(document.createElement("button"), {
      textContent: "Cancel",
      onclick: () => finishPick(null),
    }));
    banner.hidden = false;
    photoMap.map.getCanvas().style.cursor = "crosshair";
  });
}
function finishPick(v: maplibregl.LngLat | null) {
  banner.hidden = true;
  photoMap.map.getCanvas().style.cursor = "";
  pickResolve?.(v);
  pickResolve = null;
}
photoMap.onClickEmpty = (lngLat) => {
  if (pickResolve) finishPick(lngLat);
  else if (selected) select(null);
};

/* ---- header ---- */
const uploader = new Uploader(pickOnMap, (p) => photoMap.upsert(p));
const btnAccount = document.getElementById("btn-account")!;
const btnUpload = document.getElementById("btn-upload")!;

function refreshHeader() {
  const u = currentUser();
  btnAccount.textContent = u ? u.name || "Account" : "Sign in";
  // the panel offers Delete vs Report depending on who is signed in
  if (selected) panel.show(selected);
}
btnAccount.addEventListener("click", () => (currentUser() ? openAccount(refreshHeader) : openAuth(refreshHeader)));
btnUpload.addEventListener("click", () => (currentUser() ? uploader.open() : openAuth(() => (refreshHeader(), uploader.open()))));

window.addEventListener("hashchange", () => {
  const { photo } = readHash();
  if (photo !== selected) select(photo, true);
});

refreshHeader();
if (pb.authStore.isValid) pb.collection("users").authRefresh().catch(() => (pb.authStore.clear(), refreshHeader()));
// open a deep-linked photo once the map's layers exist
if (initial.photo) photoMap.whenReady(() => select(initial.photo, !initial.view));
pb.health.check().catch(() => toast(`Server unreachable: ${pb.baseURL}`));
