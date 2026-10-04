/// <reference path="../../pb_data/types.d.ts" />

// Shared logic for clustering and the alignment job queue. Loaded with
// require() inside each handler: PocketBase runs handlers in isolated VMs,
// so top-level functions in *.pb.js files are not visible to them.

const LINK_M = 40;        // photos closer than this join one place
const MIN_PHOTOS = 5;     // smallest place worth aligning
const LEASE_MIN = 60;     // a worker must heartbeat within this
const MAX_ATTEMPTS = 5;

function iso(ms) {
  return new Date(ms).toISOString().replace("T", " ");
}

function bad(msg) {
  return new BadRequestError(msg);
}

/** Group photos into places by proximity and queue alignment for changed places (PRD A1). */
function cluster(app) {
  // 0.1 placeholders: an integer literal would make the scanner expect int64
  const rows = arrayOf(new DynamicModel({ id: "", lat: 0.1, lon: 0.1, place: "" }));
  app.db().newQuery("SELECT id, lat, lon, place FROM photos WHERE hidden = FALSE").all(rows);
  const n = rows.length;
  const parent = new Array(n);
  for (let i = 0; i < n; i++) parent[i] = i;
  const find = (i) => {
    while (parent[i] !== i) i = parent[i] = parent[parent[i]];
    return i;
  };

  // grid hash: compare each photo only with photos in neighbouring cells
  const xy = rows.map((r) => [r.lon * 111320 * Math.cos((r.lat * Math.PI) / 180), r.lat * 110540]);
  const cells = {};
  xy.forEach(([x, y], i) => {
    const k = Math.floor(x / LINK_M) + ":" + Math.floor(y / LINK_M);
    (cells[k] = cells[k] || []).push(i);
  });
  xy.forEach(([x, y], i) => {
    const cx = Math.floor(x / LINK_M), cy = Math.floor(y / LINK_M);
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      for (const j of cells[cx + dx + ":" + (cy + dy)] || []) {
        if (j <= i) continue;
        if (Math.hypot(xy[j][0] - x, xy[j][1] - y) < LINK_M) parent[find(i)] = find(j);
      }
    }
  });

  const comps = {};
  for (let i = 0; i < n; i++) (comps[find(i)] = comps[find(i)] || []).push(i);

  const touched = new Set();
  for (const members of Object.values(comps)) {
    if (members.length < MIN_PHOTOS) continue;
    const votes = {};
    for (const i of members) if (rows[i].place) votes[rows[i].place] = (votes[rows[i].place] || 0) + 1;
    let placeId = Object.keys(votes).sort((a, b) => votes[b] - votes[a])[0];
    if (!placeId) {
      const place = new Record(app.findCollectionByNameOrId("places"));
      place.set("status", "pending");
      app.save(place);
      placeId = place.id;
    }
    for (const i of members) {
      if (rows[i].place === placeId) continue;
      if (rows[i].place) touched.add(rows[i].place);
      const photo = app.findRecordById("photos", rows[i].id);
      photo.set("place", placeId);
      app.save(photo);
      touched.add(placeId);
    }
    // centroid and count always refreshed, cheap
    const place = app.findRecordById("places", placeId);
    place.set("lat", members.reduce((s, i) => s + rows[i].lat, 0) / members.length);
    place.set("lon", members.reduce((s, i) => s + rows[i].lon, 0) / members.length);
    if (place.getInt("photo_count") !== members.length) touched.add(placeId);
    place.set("photo_count", members.length);
    if (touched.has(placeId)) place.set("dirty", true);
    app.save(place);
  }
  for (const id of touched) {
    // places that lost members elsewhere
    const place = app.findRecordById("places", id);
    const count = app.countRecords("photos", $dbx.hashExp({ place: id, hidden: false }));
    if (count !== place.getInt("photo_count")) {
      place.set("photo_count", count);
      place.set("dirty", true);
      app.save(place);
    }
  }
  return { photos: n, places: Object.values(comps).filter((m) => m.length >= MIN_PHOTOS).length, queued: enqueue(app) };
}

function enqueue(app) {
  let queued = 0;
  const dirty = app.findRecordsByFilter("places", "dirty = true && photo_count >= {:min}", "", 0, 0, { min: MIN_PHOTOS });
  for (const place of dirty) {
    const open = app.findRecordsByFilter("jobs", "place = {:p} && (status = 'queued' || status = 'leased')", "", 1, 0, { p: place.id });
    if (open.length) continue; // re-queued after this job finishes, place stays dirty
    const ids = app.findRecordsByFilter("photos", "place = {:p} && hidden = false", "", 0, 0, { p: place.id }).map((r) => r.id);
    const job = new Record(app.findCollectionByNameOrId("jobs"));
    job.set("kind", "align");
    job.set("place", place.id);
    job.set("status", "queued");
    job.set("photo_ids", ids);
    job.set("attempts", 0);
    app.save(job);
    place.set("dirty", false);
    place.set("status", "queued");
    app.save(place);
    queued++;
  }
  return queued;
}

const PHOTO_FIELDS = ["lat", "lon", "altitude", "gps_accuracy", "heading", "has_heading", "heading_accuracy",
  "pitch", "roll", "has_tilt", "fov_h", "fov_v", "focal_length_35mm", "width", "height", "captured_at"];

/** Lease the oldest open job to `worker`. Returns null when there is nothing to do. */
function claim(app, worker) {
  let out = null;
  app.runInTransaction((tx) => {
    const now = iso(Date.now());
    const jobs = tx.findRecordsByFilter("jobs",
      "attempts < {:max} && (status = 'queued' || (status = 'leased' && lease_until < {:now}))",
      "created", 1, 0, { max: MAX_ATTEMPTS, now });
    if (!jobs.length) return;
    const job = jobs[0];
    job.set("status", "leased");
    job.set("worker", worker.id);
    job.set("lease_until", iso(Date.now() + LEASE_MIN * 60000));
    job.set("attempts", job.getInt("attempts") + 1);
    tx.save(job);

    const ids = JSON.parse(toString(job.get("photo_ids")) || "[]");
    const photos = [];
    for (const id of ids) {
      let p;
      try {
        p = tx.findRecordById("photos", id);
      } catch (_) {
        continue; // deleted since the job was queued
      }
      if (p.getBool("hidden")) continue;
      const item = { id: p.id, image: `/api/files/${p.collection().id}/${p.id}/${p.getString("image")}` };
      for (const f of PHOTO_FIELDS) item[f] = p.get(f);
      photos.push(item);
    }
    out = { job: job.id, kind: job.getString("kind"), place: job.getString("place"),
            lease_until: job.getString("lease_until"), photos };
  });
  return out;
}

function leasedBy(app, jobId, worker) {
  const job = app.findRecordById("jobs", jobId);
  if (job.getString("status") !== "leased" || job.getString("worker") !== worker.id) {
    throw bad("job is not leased by you");
  }
  return job;
}

function heartbeat(app, jobId, worker) {
  const job = leasedBy(app, jobId, worker);
  job.set("lease_until", iso(Date.now() + LEASE_MIN * 60000));
  app.save(job);
  return { lease_until: job.getString("lease_until") };
}

function fail(app, jobId, worker, error) {
  const job = leasedBy(app, jobId, worker);
  const final = job.getInt("attempts") >= MAX_ATTEMPTS;
  job.set("status", final ? "failed" : "queued");
  job.set("error", String(error || "").slice(0, 5000));
  app.save(job);
  if (final) {
    const place = app.findRecordById("places", job.getString("place"));
    place.set("status", "failed");
    app.save(place);
  }
  return { status: job.getString("status") };
}

const POSE_NUMS = ["x", "y", "z", "qx", "qy", "qz", "qw", "fx", "fy", "cx", "cy", "k1",
  "num_points", "error_px", "median_depth", "gps_residual_m", "heading"];

function validate(job, result) {
  const allowed = new Set(JSON.parse(toString(job.get("photo_ids")) || "[]"));
  if (!result || !Array.isArray(result.poses)) throw bad("result.poses missing");
  const o = result.origin || {};
  for (const k of ["lat", "lon", "alt"]) if (!Number.isFinite(o[k])) throw bad("origin." + k + " invalid");
  for (const p of result.poses) {
    if (!allowed.has(p.photo)) throw bad("pose for a photo outside this job: " + p.photo);
    for (const k of ["x", "y", "z", "qx", "qy", "qz", "qw"]) {
      if (!Number.isFinite(p[k]) || Math.abs(p[k]) > 1e5) throw bad(`pose ${p.photo}: ${k} invalid`);
    }
  }
}

/** Accept a result: trusted workers' results apply immediately, others wait for review. */
function submit(app, jobId, worker, result, pointsFile) {
  const job = leasedBy(app, jobId, worker);
  validate(job, result);
  if (!worker.getBool("trusted_worker")) {
    job.set("status", "review");
    job.set("result", result);
    if (pointsFile) job.set("result_points", pointsFile);
    app.save(job);
    return { status: "review" };
  }
  applyResult(app, job, result, pointsFile);
  return { status: "done" };
}

function applyResult(app, job, result, pointsFile) {
  app.runInTransaction((tx) => {
    const placeId = job.getString("place");
    for (const old of tx.findRecordsByFilter("poses", "place = {:p}", "", 0, 0, { p: placeId })) tx.delete(old);
    const col = tx.findCollectionByNameOrId("poses");
    for (const p of result.poses) {
      const r = new Record(col);
      r.set("place", placeId);
      r.set("photo", p.photo);
      r.set("ok", !!p.ok);
      for (const k of POSE_NUMS) if (Number.isFinite(p[k])) r.set(k, p[k]);
      tx.save(r);
    }
    const place = tx.findRecordById("places", placeId);
    place.set("origin_lat", result.origin.lat);
    place.set("origin_lon", result.origin.lon);
    place.set("origin_alt", result.origin.alt);
    place.set("registered_count", result.poses.length);
    place.set("ok_count", result.poses.filter((p) => p.ok).length);
    place.set("points_count", (result.stats && result.stats.points) || 0);
    if (pointsFile) place.set("points", pointsFile);
    place.set("aligned_at", iso(Date.now()));
    place.set("stats", result.stats || {});
    place.set("status", "aligned");
    tx.save(place);
    job.set("status", "done");
    job.set("error", "");
    tx.save(job);
  });
}

/** Superuser review: apply a result an untrusted worker submitted. */
function approve(app, jobId) {
  const job = app.findRecordById("jobs", jobId);
  if (job.getString("status") !== "review") throw new Error("job is not awaiting review");
  const result = JSON.parse(toString(job.get("result")));
  let file = null;
  const name = job.getString("result_points");
  if (name) {
    const fs = app.newFilesystem();
    try {
      const blob = fs.getReader(job.baseFilesPath() + "/" + name);
      file = $filesystem.fileFromBytes(toBytes(blob), "points.bin");
      blob.close();
    } finally {
      fs.close();
    }
  }
  applyResult(app, job, result, file);
}

module.exports = { cluster, enqueue, claim, heartbeat, fail, submit, approve };
