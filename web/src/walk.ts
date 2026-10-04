import * as THREE from "three";
import { getPlace, imageUrl, placePoses, pointsUrl, type Place, type Pose } from "./api";
import { h } from "./ui";

/*
 * Walk-through viewer (PRD W1, W2). Each aligned photo is a textured quad
 * placed at its median scene depth in front of its camera pose. Moving to
 * another photo flies the virtual camera from one pose to the other while
 * the two quads crossfade, so shared structure lines up during the move.
 *
 * Frames: poses are east-north-up metres (x right, y down, z forward camera
 * axes, as COLMAP). three.js is y-up, so ENU (e, n, u) maps to (e, u, -n).
 */

type Dir = "forward" | "back" | "left" | "right" | "turnLeft" | "turnRight";

interface View {
  pose: Pose;
  pos: THREE.Vector3;
  quat: THREE.Quaternion; // three.js camera orientation
  fwd: THREE.Vector3;
  right: THREE.Vector3;
  heading: number; // degrees, clockwise from north
  hfov: number; // radians
  vfov: number;
  depth: number;
  mesh?: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial>;
  loading?: Promise<void>;
}

const enu = (v: THREE.Vector3) => new THREE.Vector3(v.x, v.z, -v.y);
const DURATION = 900;
const ARROWS: Record<Dir, { label: string; key: string }> = {
  forward: { label: "↑", key: "ArrowUp" },
  back: { label: "↓", key: "ArrowDown" },
  left: { label: "←", key: "ArrowLeft" },
  right: { label: "→", key: "ArrowRight" },
  turnLeft: { label: "⟲", key: "q" },
  turnRight: { label: "⟳", key: "e" },
};

function makeView(p: Pose): View {
  const photo = p.expand!.photo!;
  const aspect = photo.width && photo.height ? photo.width / photo.height : 4 / 3;
  const m = new THREE.Matrix4().makeRotationFromQuaternion(new THREE.Quaternion(p.qx, p.qy, p.qz, p.qw));
  const col = (i: number) => enu(new THREE.Vector3().setFromMatrixColumn(m, i)).normalize();
  const right = col(0), down = col(1), fwd = col(2);
  const quat = new THREE.Quaternion().setFromRotationMatrix(
    new THREE.Matrix4().makeBasis(right, down.clone().negate(), fwd.clone().negate()),
  );
  return {
    pose: p,
    pos: enu(new THREE.Vector3(p.x, p.y, p.z)),
    quat,
    fwd,
    right,
    heading: ((Math.atan2(fwd.x, -fwd.z) * 180) / Math.PI + 360) % 360,
    hfov: 2 * Math.atan(1 / (2 * p.fx)),
    vfov: 2 * Math.atan(1 / aspect / (2 * p.fx)),
    depth: p.median_depth > 0.5 ? p.median_depth : 10,
  };
}

const wrap = (deg: number) => ((deg + 540) % 360) - 180;

/** Best neighbouring photo in each direction, from relative position and heading. */
export function neighbours(cur: View, all: View[]): Partial<Record<Dir, View>> {
  const best: Partial<Record<Dir, [number, View]>> = {};
  const offer = (dir: Dir, score: number, v: View) => {
    if (!best[dir] || score < best[dir]![0]) best[dir] = [score, v];
  };
  const near = Math.max(1.5, 0.12 * cur.depth);
  for (const v of all) {
    if (v === cur) continue;
    const d = v.pos.clone().sub(cur.pos);
    const dx = d.dot(cur.right);
    const dz = d.dot(cur.fwd);
    const dist = Math.hypot(dx, dz);
    const turn = wrap(v.heading - cur.heading);
    if (dist < near && Math.abs(turn) >= 20 && Math.abs(turn) <= 160) {
      offer(turn > 0 ? "turnRight" : "turnLeft", Math.abs(turn) + dist, v);
    } else if (Math.abs(turn) < 50 && dist > 0.3) {
      if (dz > 0 && Math.abs(dx) < 0.8 * dz) offer("forward", dz + 2 * Math.abs(dx) + 0.05 * Math.abs(turn), v);
      else if (dz < 0 && Math.abs(dx) < 0.8 * -dz) offer("back", -dz + 2 * Math.abs(dx) + 0.05 * Math.abs(turn), v);
      else if (Math.abs(dx) >= 0.8 * Math.abs(dz)) offer(dx > 0 ? "right" : "left", dist + 0.05 * Math.abs(turn), v);
    }
  }
  return Object.fromEntries(Object.entries(best).map(([k, [, v]]) => [k, v])) as Partial<Record<Dir, View>>;
}

export class WalkViewer {
  private root: HTMLDivElement;
  private renderer: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera = new THREE.PerspectiveCamera(50, 1, 0.05, 5000);
  private views: View[] = [];
  private current?: View;
  private animating = false;
  private points?: THREE.Points;
  private arrowEls: Partial<Record<Dir, HTMLButtonElement>> = {};
  private title = h("span", { class: "walk-title" });
  private counter = h("span", { class: "walk-count dim" });
  private loader = new THREE.TextureLoader().setCrossOrigin("anonymous");
  private place?: Place;
  onPhoto: (photoId: string) => void = () => {};
  onClose: (photoId: string | null) => void = () => {};

  constructor() {
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.scene.background = new THREE.Color(0x000000);

    const arrows = h("div", { class: "walk-arrows" });
    for (const dir of Object.keys(ARROWS) as Dir[]) {
      const b = h("button", { class: `walk-arrow ${dir}`, title: dir, onclick: () => this.go(dir) }, ARROWS[dir].label);
      this.arrowEls[dir] = b;
      arrows.append(b);
    }
    this.root = h(
      "div",
      { class: "walk", hidden: true },
      this.renderer.domElement,
      h(
        "div",
        { class: "walk-bar" },
        this.title,
        this.counter,
        h("button", { onclick: () => this.togglePoints() }, "Points"),
        h("button", { class: "glow", onclick: () => this.close() }, "Map"),
      ),
      arrows,
    );
    document.body.append(this.root);
    this.renderer.domElement.addEventListener("click", (e) => this.clickToMove(e));
    window.addEventListener("resize", () => this.resize());
    window.addEventListener("keydown", (e) => {
      if (this.root.hidden) return;
      if (e.key === "Escape") return this.close();
      const dir = (Object.keys(ARROWS) as Dir[]).find((d) => ARROWS[d].key === e.key);
      if (dir) {
        e.preventDefault();
        this.go(dir);
      }
    });
  }

  get isOpen() {
    return !this.root.hidden;
  }

  async open(placeId: string, photoId?: string | null) {
    this.root.hidden = false;
    this.resize();
    if (this.place?.id !== placeId) {
      this.clear();
      this.place = await getPlace(placeId);
      const poses = await placePoses(placeId);
      this.views = poses.filter((p) => p.expand?.photo).map(makeView);
      if (this.place.points) this.loadPoints(this.place);
    }
    if (!this.views.length) {
      this.title.textContent = "No aligned photos in this place yet";
      return;
    }
    const start = this.views.find((v) => v.pose.photo === photoId) ?? this.views[0];
    await this.load(start);
    this.show(start);
    this.loop();
  }

  close() {
    this.root.hidden = true;
    this.onClose(this.current?.pose.photo ?? null);
  }

  private clear() {
    for (const v of this.views) {
      if (v.mesh) {
        this.scene.remove(v.mesh);
        v.mesh.material.map?.dispose();
        v.mesh.material.dispose();
        v.mesh.geometry.dispose();
      }
    }
    if (this.points) {
      this.scene.remove(this.points);
      this.points.geometry.dispose();
      this.points = undefined;
    }
    this.views = [];
    this.current = undefined;
  }

  private resize() {
    const w = window.innerWidth, hgt = window.innerHeight;
    this.renderer.setSize(w, hgt);
    this.camera.aspect = w / hgt;
    if (this.current) this.camera.fov = this.fitFov(this.current);
    this.camera.updateProjectionMatrix();
  }

  /** Vertical fov that shows the whole photo inside the viewport. */
  private fitFov(v: View) {
    const tanV = Math.max(Math.tan(v.vfov / 2), Math.tan(v.hfov / 2) / this.camera.aspect);
    return (2 * Math.atan(tanV) * 180) / Math.PI;
  }

  private load(v: View): Promise<void> {
    if (v.loading) return v.loading;
    const photo = v.pose.expand!.photo!;
    v.loading = this.loader.loadAsync(imageUrl(photo, "1600x0")).then((tex) => {
      tex.colorSpace = THREE.SRGBColorSpace;
      const width = v.depth / v.pose.fx;
      const height = (width * tex.image.height) / tex.image.width;
      const mesh = new THREE.Mesh(
        new THREE.PlaneGeometry(width, height),
        new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0, depthTest: false, depthWrite: false }),
      );
      mesh.quaternion.copy(v.quat);
      // principal point offset, normalised by width (cx) and height (cy)
      const offset = v.right.clone().multiplyScalar((0.5 - v.pose.cx) * width);
      const down = new THREE.Vector3(0, -1, 0).applyQuaternion(v.quat);
      offset.add(down.multiplyScalar((0.5 - v.pose.cy) * height));
      mesh.position.copy(v.pos).add(v.fwd.clone().multiplyScalar(v.depth)).add(offset);
      mesh.visible = false;
      this.scene.add(mesh);
      v.mesh = mesh;
    });
    return v.loading;
  }

  private show(v: View) {
    for (const o of this.views) if (o.mesh && o !== v) o.mesh.visible = false;
    v.mesh!.visible = true;
    v.mesh!.material.opacity = 1;
    v.mesh!.renderOrder = 1;
    this.camera.position.copy(v.pos);
    this.camera.quaternion.copy(v.quat);
    this.camera.fov = this.fitFov(v);
    this.camera.updateProjectionMatrix();
    this.setCurrent(v);
  }

  private setCurrent(v: View) {
    this.current = v;
    const photo = v.pose.expand!.photo!;
    this.title.textContent = photo.title || "Untitled";
    this.counter.textContent = `${this.views.indexOf(v) + 1} / ${this.views.length}`;
    const nb = neighbours(v, this.views);
    for (const dir of Object.keys(ARROWS) as Dir[]) this.arrowEls[dir]!.hidden = !nb[dir];
    // warm the cache for the next likely moves
    for (const n of Object.values(nb)) if (n) this.load(n);
    this.onPhoto(v.pose.photo);
  }

  async go(dir: Dir) {
    if (!this.current || this.animating) return;
    const target = neighbours(this.current, this.views)[dir];
    if (target) await this.flyTo(target);
  }

  private async flyTo(to: View) {
    const from = this.current!;
    this.animating = true;
    await this.load(to);
    const a = from.mesh!, b = to.mesh!;
    b.visible = true;
    b.renderOrder = 2;
    a.renderOrder = 1;
    const p0 = this.camera.position.clone(), q0 = this.camera.quaternion.clone(), f0 = this.camera.fov;
    const f1 = this.fitFov(to);
    const t0 = performance.now();
    await new Promise<void>((done) => {
      const step = () => {
        const t = Math.min(1, (performance.now() - t0) / DURATION);
        const e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        this.camera.position.lerpVectors(p0, to.pos, e);
        this.camera.quaternion.slerpQuaternions(q0, to.quat, e);
        this.camera.fov = f0 + (f1 - f0) * e;
        this.camera.updateProjectionMatrix();
        a.material.opacity = 1 - e;
        b.material.opacity = e;
        if (t < 1) requestAnimationFrame(step);
        else done();
      };
      requestAnimationFrame(step);
    });
    this.show(to);
    this.animating = false;
  }

  /** Click on part of the photo: move to the photo that looks most directly at that spot (PRD W3). */
  private clickToMove(e: MouseEvent) {
    if (!this.current || this.animating) return;
    const ndc = new THREE.Vector2((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(ndc, this.camera);
    const target = this.current.pos.clone().add(ray.ray.direction.clone().multiplyScalar(this.current.depth));
    let best: View | undefined, bestScore = Infinity;
    for (const v of this.views) {
      if (v === this.current) continue;
      const to = target.clone().sub(v.pos);
      const dist = to.length();
      const angle = v.fwd.angleTo(to.normalize());
      if (angle > 0.25 || dist > this.current.depth * 1.2) continue;
      const score = angle * 10 + dist / this.current.depth;
      if (score < bestScore) [best, bestScore] = [v, score];
    }
    if (best) this.flyTo(best);
  }

  private async loadPoints(place: Place) {
    const buf = await fetch(pointsUrl(place)).then((r) => r.arrayBuffer());
    const n = Math.floor(buf.byteLength / 15);
    const view = new DataView(buf);
    const pos = new Float32Array(n * 3), col = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const o = i * 15;
      const x = view.getFloat32(o, true), y = view.getFloat32(o + 4, true), z = view.getFloat32(o + 8, true);
      pos.set([x, z, -y], i * 3);
      col.set([view.getUint8(o + 12) / 255, view.getUint8(o + 13) / 255, view.getUint8(o + 14) / 255], i * 3);
    }
    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    geom.setAttribute("color", new THREE.BufferAttribute(col, 3));
    this.points = new THREE.Points(
      geom,
      new THREE.PointsMaterial({ size: 2, sizeAttenuation: false, vertexColors: true, transparent: true, opacity: 0.85 }),
    );
    this.points.visible = false;
    this.points.renderOrder = 3;
    (this.points.material as THREE.PointsMaterial).depthTest = false;
    this.scene.add(this.points);
  }

  private togglePoints() {
    if (this.points) this.points.visible = !this.points.visible;
  }

  private looping = false;
  private loop() {
    if (this.looping) return;
    this.looping = true;
    const frame = () => {
      if (this.root.hidden) {
        this.looping = false;
        return;
      }
      this.renderer.render(this.scene, this.camera);
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }
}
