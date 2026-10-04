import maplibregl, { type GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { alignedPlacesInBounds, photosInBounds, type Photo } from "./api";

const STYLE = "https://tiles.openfreemap.org/styles/dark";
const EMPTY: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };

export class PhotoMap {
  readonly map: maplibregl.Map;
  private loaded = new Map<string, Photo>();
  private timer?: number;
  private ready = false;
  private readyQueue: (() => void)[] = [];
  onSelect: (id: string) => void = () => {};
  onPlace: (id: string) => void = () => {};
  onClickEmpty: (lngLat: maplibregl.LngLat) => void = () => {};
  onMove: () => void = () => {};

  constructor(container: string, view: { center: [number, number]; zoom: number }) {
    this.map = new maplibregl.Map({ container, style: STYLE, ...view, attributionControl: { compact: true } });
    this.map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "bottom-right");
    this.map.addControl(
      new maplibregl.GeolocateControl({ positionOptions: { enableHighAccuracy: true } }),
      "bottom-right",
    );
    this.collapseAttribution();
    // style.load, not load: the latter waits for every basemap tile
    this.map.once("style.load", () => this.setup());
  }

  /** MapLibre opens the compact attribution when the first source reports in; fold it to the (i) button. */
  private collapseAttribution() {
    const collapse = () => {
      const el = this.map.getContainer().querySelector(".maplibregl-ctrl-attrib");
      if (!el?.classList.contains("maplibregl-compact")) return;
      el.classList.remove("maplibregl-compact-show");
      this.map.off("sourcedata", collapse);
    };
    this.map.on("sourcedata", collapse);
  }

  private setup() {
    const m = this.map;
    m.addImage("wedge", wedgeIcon(), { pixelRatio: 2 });
    m.addSource("photos", { type: "geojson", data: EMPTY, cluster: true, clusterRadius: 44, clusterMaxZoom: 16 });
    m.addSource("selected", { type: "geojson", data: EMPTY });
    m.addSource("places", { type: "geojson", data: EMPTY });

    m.addLayer({
      id: "clusters",
      type: "circle",
      source: "photos",
      filter: ["has", "point_count"],
      paint: {
        "circle-color": "#000",
        "circle-stroke-color": "#fff",
        "circle-stroke-width": 1,
        "circle-radius": ["step", ["get", "point_count"], 14, 10, 18, 100, 24],
      },
    });
    m.addLayer({
      id: "cluster-count",
      type: "symbol",
      source: "photos",
      filter: ["has", "point_count"],
      layout: { "text-field": ["get", "point_count_abbreviated"], "text-size": 11, "text-font": ["Noto Sans Regular"] },
      paint: { "text-color": "#fff" },
    });
    // explorable places: a ring around the cluster of aligned photos (PRD M2)
    m.addLayer({
      id: "places",
      type: "circle",
      source: "places",
      paint: {
        "circle-color": "rgba(255,255,255,0.06)",
        "circle-stroke-color": "#fff",
        "circle-stroke-width": 2,
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 8, 16, 26, 19, 60],
      },
    });
    m.addLayer({
      id: "selected-cone",
      type: "fill",
      source: "selected",
      paint: { "fill-color": "#fff", "fill-opacity": 0.18 },
    });
    m.addLayer({
      id: "selected-cone-line",
      type: "line",
      source: "selected",
      paint: { "line-color": "#fff", "line-width": 1 },
    });
    m.addLayer({
      id: "headings",
      type: "symbol",
      source: "photos",
      filter: ["all", ["!", ["has", "point_count"]], ["get", "has_heading"]],
      layout: {
        "icon-image": "wedge",
        "icon-rotate": ["get", "heading"],
        "icon-rotation-alignment": "map",
        "icon-allow-overlap": true,
        "icon-anchor": "bottom",
      },
    });
    m.addLayer({
      id: "points",
      type: "circle",
      source: "photos",
      filter: ["!", ["has", "point_count"]],
      paint: { "circle-color": "#fff", "circle-radius": 4.5, "circle-stroke-color": "#000", "circle-stroke-width": 1.5 },
    });

    m.on("click", "clusters", async (e) => {
      const f = e.features?.[0];
      if (!f) return;
      const src = m.getSource("photos") as GeoJSONSource;
      const zoom = await src.getClusterExpansionZoom(f.properties.cluster_id);
      m.easeTo({ center: (f.geometry as GeoJSON.Point).coordinates as [number, number], zoom });
    });
    m.on("click", "places", (e) => {
      if (m.queryRenderedFeatures(e.point, { layers: ["points", "clusters"] }).length) return;
      const id = e.features?.[0]?.properties.id;
      if (id) this.onPlace(id);
    });
    m.on("click", "points", (e) => {
      const id = e.features?.[0]?.properties.id;
      if (id) this.onSelect(id);
    });
    m.on("click", (e) => {
      if (!m.queryRenderedFeatures(e.point, { layers: ["points", "clusters", "places"] }).length) this.onClickEmpty(e.lngLat);
    });
    for (const l of ["points", "clusters", "places"]) {
      m.on("mouseenter", l, () => (m.getCanvas().style.cursor = "pointer"));
      m.on("mouseleave", l, () => (m.getCanvas().style.cursor = ""));
    }
    m.on("moveend", () => {
      this.onMove();
      this.scheduleLoad();
    });
    this.scheduleLoad();
    this.ready = true;
    this.readyQueue.splice(0).forEach((fn) => fn());
  }

  /** Runs `fn` once the style and Stride layers exist; camera moves before that can stall the first render. */
  whenReady(fn: () => void) {
    if (this.ready) fn();
    else this.readyQueue.push(fn);
  }

  scheduleLoad() {
    clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this.load(), 250);
  }

  private async load() {
    const b = this.map.getBounds();
    try {
      const [items, places] = await Promise.all([
        photosInBounds(b.getSouth(), b.getWest(), b.getNorth(), b.getEast()),
        alignedPlacesInBounds(b.getSouth(), b.getWest(), b.getNorth(), b.getEast()).catch(() => []),
      ]);
      for (const p of items) this.loaded.set(p.id, p);
      this.render();
      (this.map.getSource("places") as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: places.map((p) => ({
          type: "Feature",
          geometry: { type: "Point", coordinates: [p.lon, p.lat] },
          properties: { id: p.id, count: p.ok_count },
        })),
      });
    } catch (err) {
      console.warn("loading photos failed", err);
    }
  }

  /** Add or replace a photo locally, e.g. right after an upload. */
  upsert(p: Photo) {
    this.loaded.set(p.id, p);
    this.render();
  }

  remove(id: string) {
    this.loaded.delete(id);
    this.render();
  }

  private render() {
    const features = [...this.loaded.values()].map(
      (p): GeoJSON.Feature => ({
        type: "Feature",
        geometry: { type: "Point", coordinates: [p.lon, p.lat] },
        properties: { id: p.id, heading: p.heading, has_heading: p.has_heading },
      }),
    );
    (this.map.getSource("photos") as GeoJSONSource | undefined)?.setData({ type: "FeatureCollection", features });
  }

  select(p: Photo | null) {
    const src = this.map.getSource("selected") as GeoJSONSource | undefined;
    if (!src) return;
    if (!p || !p.has_heading) return src.setData(EMPTY);
    src.setData({ type: "FeatureCollection", features: [viewCone(p, 60)] });
  }
}

/** Polygon showing a photo's horizontal field of view, `length` metres deep. */
function viewCone(p: Photo, length: number): GeoJSON.Feature {
  const fov = p.fov_h > 0 ? p.fov_h : 60;
  const pts: [number, number][] = [[p.lon, p.lat]];
  for (let i = 0; i <= 12; i++) {
    const bearing = ((p.heading - fov / 2 + (fov * i) / 12) * Math.PI) / 180;
    const dN = length * Math.cos(bearing);
    const dE = length * Math.sin(bearing);
    pts.push([p.lon + dE / (111320 * Math.cos((p.lat * Math.PI) / 180)), p.lat + dN / 110540]);
  }
  pts.push([p.lon, p.lat]);
  return { type: "Feature", geometry: { type: "Polygon", coordinates: [pts] }, properties: {} };
}

/** A narrow wedge pointing up from the anchor, drawn at 2x. */
function wedgeIcon(): ImageData {
  const w = 40, h = 52;
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const g = c.getContext("2d")!;
  const grad = g.createLinearGradient(0, h, 0, 0);
  grad.addColorStop(0, "rgba(255,255,255,0.75)");
  grad.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = grad;
  g.beginPath();
  g.moveTo(w / 2, h);
  g.lineTo(2, 0);
  g.lineTo(w - 2, 0);
  g.closePath();
  g.fill();
  return g.getImageData(0, 0, w, h);
}
