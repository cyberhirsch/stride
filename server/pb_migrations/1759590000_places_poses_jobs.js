/// <reference path="../pb_data/types.d.ts" />

// Alignment: places (clusters of photos), poses (per-photo result) and the
// job queue that volunteer workers pull from. Only the server writes these;
// workers go through the routes in pb_hooks/jobs.pb.js.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.add(new BoolField({ name: "trusted_worker" }));
  users.updateRule = "id = @request.auth.id && @request.body.trusted_worker:isset = false";
  app.save(users);

  const photos = app.findCollectionByNameOrId("photos");

  const places = new Collection({
    type: "base",
    name: "places",
    listRule: "",
    viewRule: "",
    fields: [
      { type: "text", name: "name", max: 200 },
      { type: "select", name: "status", required: true, maxSelect: 1, values: ["pending", "queued", "aligned", "failed"] },
      // centroid of member photos, for the map
      { type: "number", name: "lat" },
      { type: "number", name: "lon" },
      { type: "number", name: "photo_count", onlyInt: true },
      { type: "bool", name: "dirty" },
      // origin of the local east-north-up frame poses are expressed in
      { type: "number", name: "origin_lat" },
      { type: "number", name: "origin_lon" },
      { type: "number", name: "origin_alt" },
      { type: "number", name: "registered_count", onlyInt: true },
      { type: "number", name: "ok_count", onlyInt: true },
      // little-endian float32 x,y,z + uint8 r,g,b per point, ENU metres
      { type: "file", name: "points", maxSelect: 1, maxSize: 4194304 },
      { type: "number", name: "points_count", onlyInt: true },
      { type: "date", name: "aligned_at" },
      { type: "json", name: "stats", maxSize: 65536 },
      { type: "autodate", name: "created", onCreate: true, onUpdate: false },
      { type: "autodate", name: "updated", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_places_lat_lon ON places (lat, lon)"],
  });
  app.save(places);

  photos.fields.add(new RelationField({ name: "place", collectionId: places.id, maxSelect: 1 }));
  photos.createRule = "@request.auth.id != '' && @request.body.author = @request.auth.id && @request.body.hidden:isset = false && @request.body.place:isset = false";
  photos.updateRule = "author = @request.auth.id && (@request.body.author:isset = false || @request.body.author = @request.auth.id) && @request.body.hidden:isset = false && @request.body.place:isset = false";
  app.save(photos);

  const poses = new Collection({
    type: "base",
    name: "poses",
    listRule: "photo.hidden = false",
    viewRule: "photo.hidden = false",
    fields: [
      { type: "relation", name: "place", required: true, collectionId: places.id, maxSelect: 1, cascadeDelete: true },
      { type: "relation", name: "photo", required: true, collectionId: photos.id, maxSelect: 1, cascadeDelete: true },
      { type: "bool", name: "ok" },
      // camera centre, ENU metres from the place origin
      { type: "number", name: "x" },
      { type: "number", name: "y" },
      { type: "number", name: "z" },
      // camera-to-world rotation (x right, y down, z forward), quaternion
      { type: "number", name: "qx" },
      { type: "number", name: "qy" },
      { type: "number", name: "qz" },
      { type: "number", name: "qw" },
      // intrinsics normalised by image width (fx, fy, cx) and height (cy)
      { type: "number", name: "fx" },
      { type: "number", name: "fy" },
      { type: "number", name: "cx" },
      { type: "number", name: "cy" },
      { type: "number", name: "k1" },
      { type: "number", name: "num_points", onlyInt: true },
      { type: "number", name: "error_px" },
      { type: "number", name: "median_depth" },
      { type: "number", name: "gps_residual_m" },
      { type: "number", name: "heading" },
    ],
    indexes: [
      "CREATE UNIQUE INDEX idx_poses_place_photo ON poses (place, photo)",
      "CREATE INDEX idx_poses_photo ON poses (photo)",
    ],
  });
  app.save(poses);

  const jobs = new Collection({
    type: "base",
    name: "jobs",
    fields: [
      { type: "select", name: "kind", required: true, maxSelect: 1, values: ["align"] },
      { type: "relation", name: "place", required: true, collectionId: places.id, maxSelect: 1, cascadeDelete: true },
      { type: "select", name: "status", required: true, maxSelect: 1, values: ["queued", "leased", "done", "failed", "review"] },
      { type: "json", name: "photo_ids", maxSize: 2000000 },
      { type: "relation", name: "worker", collectionId: users.id, maxSelect: 1 },
      { type: "date", name: "lease_until" },
      { type: "number", name: "attempts", onlyInt: true },
      { type: "text", name: "error", max: 5000 },
      // results from untrusted workers wait here for review
      { type: "json", name: "result", maxSize: 5000000 },
      { type: "file", name: "result_points", maxSelect: 1, maxSize: 4194304 },
      { type: "autodate", name: "created", onCreate: true, onUpdate: false },
      { type: "autodate", name: "updated", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_jobs_status ON jobs (status, created)"],
  });
  app.save(jobs);
}, (app) => {
  for (const name of ["jobs", "poses"]) app.delete(app.findCollectionByNameOrId(name));
  const photos = app.findCollectionByNameOrId("photos");
  photos.fields.removeByName("place");
  photos.createRule = "@request.auth.id != '' && @request.body.author = @request.auth.id && @request.body.hidden:isset = false";
  photos.updateRule = "author = @request.auth.id && (@request.body.author:isset = false || @request.body.author = @request.auth.id) && @request.body.hidden:isset = false";
  app.save(photos);
  app.delete(app.findCollectionByNameOrId("places"));
  const users = app.findCollectionByNameOrId("users");
  users.fields.removeByName("trusted_worker");
  users.updateRule = "id = @request.auth.id";
  app.save(users);
});
