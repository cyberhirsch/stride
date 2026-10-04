import type maplibregl from "maplibre-gl";
import { currentUser, LICENSES, pb, type License, type Photo } from "./api";
import { LiveSensors, readMeta, type LiveSample, type PhotoMeta } from "./meta";
import { errorMessage, fmt, h, openModal, toast } from "./ui";

interface Item {
  file: File;
  preview: string;
  meta: PhotoMeta;
  title: string;
  status: "ready" | "uploading" | "done" | "error";
  error?: string;
}

const ACCEPT = "image/jpeg,image/png,image/webp";

/**
 * Upload dialog: pick or shoot photos, check the extracted metadata, place
 * photos without GPS on the map, then upload.
 */
export class Uploader {
  private items: Item[] = [];
  private license: License = (localStorage.getItem("stride.license") as License) || "cc-by";
  private close?: () => void;
  private sensors = new LiveSensors();

  constructor(
    private pickOnMap: (prompt: string) => Promise<maplibregl.LngLat | null>,
    private onUploaded: (p: Photo) => void,
  ) {}

  open() {
    this.render();
  }

  private render() {
    this.close?.();
    const fileInput = h("input", { type: "file", accept: ACCEPT, multiple: true, hidden: true });
    const camInput = h("input", { type: "file", accept: "image/*", capture: "environment", hidden: true });
    fileInput.addEventListener("change", () => this.add([...(fileInput.files ?? [])]));
    camInput.addEventListener("change", async () => {
      const live = { ...this.sensors.sample };
      this.sensors.stop();
      await this.add([...(camInput.files ?? [])], live);
    });

    const licenseSelect = h(
      "select",
      { onchange: (e: Event) => this.setLicense((e.target as HTMLSelectElement).value as License) },
      ...Object.entries(LICENSES).map(([v, label]) => h("option", { value: v, selected: v === this.license }, label)),
    );

    const pending = this.items.filter((i) => i.status !== "done");
    const ready = pending.filter((i) => i.meta.lat !== undefined);
    const uploadBtn = h(
      "button",
      { class: "glow", disabled: !ready.length, onclick: () => this.uploadAll() },
      ready.length ? `Upload ${ready.length}` : "Upload",
    );

    const body = h(
      "div",
      { class: "stack" },
      h(
        "div",
        { class: "button-row-2" },
        h(
          "button",
          {
            onclick: async () => {
              await this.sensors.start();
              camInput.click();
            },
          },
          "Take photo",
        ),
        h("button", { onclick: () => fileInput.click() }, "Choose files"),
      ),
      fileInput,
      camInput,
      h("label", {}, "License", licenseSelect),
      this.items.length
        ? h("div", { class: "upload-list" }, ...this.items.map((it) => this.card(it)))
        : h("p", { class: "dim" }, "JPEG, PNG or WebP. Position, heading and lens data are read from the photo's EXIF."),
    );
    this.close = openModal("Add photos", body, uploadBtn, () => (this.close = undefined));
  }

  private setLicense(l: License) {
    this.license = l;
    localStorage.setItem("stride.license", l);
  }

  private async add(files: File[], live?: LiveSample) {
    for (const file of files) {
      if (/heic|heif/i.test(file.type) || /\.hei[cf]$/i.test(file.name)) {
        toast(`${file.name}: HEIC is not supported, export as JPEG`);
        continue;
      }
      try {
        const meta = await readMeta(file);
        if (live) applyLive(meta, live);
        this.items.push({ file, meta, preview: URL.createObjectURL(file), title: "", status: "ready" });
      } catch (e) {
        toast(`${file.name}: ${errorMessage(e)}`);
      }
    }
    this.render();
  }

  private card(it: Item) {
    const m = it.meta;
    const pos = m.lat !== undefined ? `${m.lat.toFixed(5)}, ${m.lon!.toFixed(5)}` : "no position";
    const facts = [
      pos + (m.gps_accuracy ? ` ±${m.gps_accuracy.toFixed(0)} m` : ""),
      m.heading !== undefined ? `heading ${fmt.deg(m.heading)} ${fmt.compass(m.heading)}` : "no heading",
      m.fov_h ? `fov ${fmt.deg(m.fov_h)}` : "",
      m.captured_at.toLocaleString(),
    ].filter(Boolean);

    const heading = h("input", {
      type: "number",
      min: 0,
      max: 359,
      step: 1,
      placeholder: "°",
      value: m.heading !== undefined ? Math.round(m.heading) : "",
      onchange: (e: Event) => {
        const v = parseFloat((e.target as HTMLInputElement).value);
        m.heading = Number.isFinite(v) ? ((v % 360) + 360) % 360 : undefined;
        m.sources.heading = "manual";
      },
    });

    return h(
      "div",
      { class: `upload-item ${it.status}` },
      h("img", { src: it.preview, alt: "" }),
      h(
        "div",
        { class: "stack tight" },
        h("input", {
          type: "text",
          placeholder: "Title (optional)",
          value: it.title,
          maxLength: 200,
          oninput: (e: Event) => (it.title = (e.target as HTMLInputElement).value),
        }),
        h("p", { class: "dim small" }, facts.join(" · ")),
        h(
          "div",
          { class: "row" },
          h("button", { class: "small", onclick: () => this.place(it) }, m.lat === undefined ? "Place on map" : "Move"),
          h("label", { class: "inline" }, "Heading", heading),
          it.status === "ready" || it.status === "error"
            ? h("button", { class: "small", onclick: () => this.drop(it) }, "Remove")
            : null,
        ),
        it.status === "error" ? h("p", { class: "error small" }, it.error ?? "") : null,
        it.status === "uploading" ? h("p", { class: "dim small" }, "Uploading…") : null,
        it.status === "done" ? h("p", { class: "small" }, "Uploaded") : null,
      ),
    );
  }

  private drop(it: Item) {
    URL.revokeObjectURL(it.preview);
    this.items = this.items.filter((i) => i !== it);
    this.render();
  }

  private async place(it: Item) {
    this.close?.();
    const at = await this.pickOnMap(`Click where ${it.file.name} was taken`);
    if (at) {
      it.meta.lat = at.lat;
      it.meta.lon = at.lng;
      it.meta.gps_accuracy = undefined;
      it.meta.sources.position = "manual";
    }
    this.render();
  }

  private async uploadAll() {
    const user = currentUser();
    if (!user) return toast("Sign in first");
    const queue = this.items.filter((i) => i.status !== "done" && i.meta.lat !== undefined);
    for (const it of queue) {
      it.status = "uploading";
      this.render();
      try {
        const rec = await pb.collection("photos").create<Photo>(formFor(it, user.id, this.license));
        it.status = "done";
        this.onUploaded(rec);
      } catch (e) {
        it.status = "error";
        it.error = errorMessage(e);
      }
    }
    const done = queue.filter((i) => i.status === "done").length;
    toast(`${done} of ${queue.length} uploaded`);
    this.items = this.items.filter((i) => i.status !== "done");
    this.render();
  }
}

function applyLive(m: PhotoMeta, live: LiveSample) {
  if (m.lat === undefined && live.lat !== undefined) {
    m.lat = live.lat;
    m.lon = live.lon;
    m.gps_accuracy = live.gps_accuracy;
    m.altitude = live.altitude;
    m.sources.position = "web-geolocation";
  }
  if (m.heading === undefined && live.heading !== undefined) {
    m.heading = live.heading;
    m.heading_accuracy = 20;
    m.sources.heading = live.heading_source ?? "web";
  }
}

function formFor(it: Item, author: string, license: License) {
  const m = it.meta;
  const fd = new FormData();
  const set = (k: string, v: unknown) => v !== undefined && v !== null && v !== "" && fd.append(k, String(v));
  set("author", author);
  fd.set("image", it.file);
  set("title", it.title.trim());
  set("license", license);
  set("platform", "web");
  set("device", m.device);
  set("captured_at", m.captured_at.toISOString());
  set("lat", m.lat);
  set("lon", m.lon);
  set("gps_accuracy", m.gps_accuracy);
  set("altitude", m.altitude);
  set("heading", m.heading);
  set("heading_accuracy", m.heading_accuracy);
  set("has_heading", m.heading !== undefined);
  set("pitch", m.pitch);
  set("roll", m.roll);
  set("has_tilt", m.pitch !== undefined && m.roll !== undefined);
  set("focal_length_mm", m.focal_length_mm);
  set("focal_length_35mm", m.focal_length_35mm);
  set("fov_h", m.fov_h);
  set("fov_v", m.fov_v);
  set("width", m.width);
  set("height", m.height);
  set("sensors", JSON.stringify({ sources: m.sources, user_agent: navigator.userAgent }));
  return fd;
}
