import { currentUser, getPhoto, imageUrl, LICENSES, pb, poseOfPhoto, type Photo } from "./api";
import { errorMessage, fmt, h, openModal, toast } from "./ui";

const REASONS = {
  person: "Shows me or another person",
  property: "Shows my private property",
  abuse: "Abusive or illegal content",
  copyright: "Copyright infringement",
  other: "Other",
};

export class PhotoPanel {
  private el = document.getElementById("panel")!;
  onClose: () => void = () => {};
  onDeleted: (id: string) => void = () => {};
  onWalk: (placeId: string, photoId: string) => void = () => {};

  async show(id: string): Promise<Photo | null> {
    this.el.hidden = false;
    this.el.replaceChildren(h("p", { class: "dim pad" }, "Loading…"));
    try {
      const p = await getPhoto(id);
      this.render(p);
      return p;
    } catch {
      this.el.replaceChildren(this.header(), h("p", { class: "dim pad" }, "Photo not found."));
      return null;
    }
  }

  hide() {
    this.el.hidden = true;
    this.el.replaceChildren();
  }

  private header(title = "") {
    return h(
      "div",
      { class: "panel-header" },
      h("h2", {}, title || "Photo"),
      h("button", { class: "icon", "aria-label": "Close", onclick: () => this.onClose() }, "×"),
    );
  }

  private render(p: Photo) {
    const author = p.expand?.author;
    const own = currentUser()?.id === p.author;
    const rows: [string, string][] = [
      ["Taken", fmt.date(p.captured_at)],
      ["By", author?.name || "Anonymous"],
      ["License", LICENSES[p.license]],
      ["Position", `${p.lat.toFixed(6)}, ${p.lon.toFixed(6)}${p.gps_accuracy ? ` ±${p.gps_accuracy.toFixed(0)} m` : ""}`],
    ];
    if (p.altitude) rows.push(["Altitude", `${p.altitude.toFixed(0)} m`]);
    if (p.has_heading)
      rows.push([
        "Heading",
        `${fmt.deg(p.heading)} ${fmt.compass(p.heading)}${p.heading_accuracy ? ` ±${fmt.deg(p.heading_accuracy)}` : ""}`,
      ]);
    if (p.has_tilt) rows.push(["Pitch / roll", `${fmt.deg(p.pitch, 1)} / ${fmt.deg(p.roll, 1)}`]);
    if (p.fov_h) rows.push(["Field of view", `${fmt.deg(p.fov_h)} × ${fmt.deg(p.fov_v)}`]);
    if (p.focal_length_35mm) rows.push(["Focal length", `${p.focal_length_35mm.toFixed(0)} mm (35 mm eq.)`]);
    if (p.width) rows.push(["Size", `${p.width} × ${p.height}`]);
    if (p.device) rows.push(["Device", `${p.device} · ${p.platform}`]);

    const walk = h("button", { class: "glow", hidden: true }, "Walk through");
    poseOfPhoto(p.id)
      .then((pose) => {
        if (!pose) return;
        walk.hidden = false;
        walk.onclick = () => this.onWalk(pose.place, p.id);
      })
      .catch(() => {});
    this.el.replaceChildren(
      this.header(p.title),
      h(
        "a",
        { href: imageUrl(p), target: "_blank", rel: "noopener", class: "panel-image" },
        h("img", { src: imageUrl(p, "1600x0"), alt: p.title || "Photo" }),
      ),
      h("dl", { class: "meta" }, ...rows.flatMap(([k, v]) => [h("dt", {}, k), h("dd", {}, v)])),
      h(
        "div",
        { class: "panel-actions" },
        walk,
        h("button", { onclick: () => this.share(p) }, "Copy link"),
        own
          ? h("button", { onclick: () => this.remove(p) }, "Delete")
          : h("button", { onclick: () => this.report(p) }, "Report"),
      ),
    );
  }

  private async share(p: Photo) {
    const url = new URL(location.href);
    url.hash = `photo=${p.id}`;
    await navigator.clipboard.writeText(url.toString()).catch(() => {});
    toast("Link copied");
  }

  private async remove(p: Photo) {
    if (!confirm("Delete this photo permanently?")) return;
    try {
      await pb.collection("photos").delete(p.id);
      toast("Photo deleted");
      this.onDeleted(p.id);
    } catch (e) {
      toast(errorMessage(e));
    }
  }

  private report(p: Photo) {
    const select = h(
      "select",
      {},
      ...Object.entries(REASONS).map(([v, label]) => h("option", { value: v }, label)),
    );
    const note = h("textarea", { rows: 3, placeholder: "Details (optional)" });
    const send = h("button", { class: "glow" }, "Send report");
    const close = openModal(
      "Report photo",
      h("div", { class: "stack" }, h("label", {}, "Reason", select), h("label", {}, "Note", note)),
      send,
    );
    send.addEventListener("click", async () => {
      send.disabled = true;
      try {
        await pb.collection("reports").create({
          photo: p.id,
          reason: select.value,
          note: note.value,
          reporter: currentUser()?.id ?? "",
        });
        close();
        toast("Thanks, the report was sent");
      } catch (e) {
        toast(errorMessage(e));
        send.disabled = false;
      }
    });
  }
}
