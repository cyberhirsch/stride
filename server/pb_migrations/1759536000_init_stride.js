/// <reference path="../pb_data/types.d.ts" />

// Stride schema v1: photos with capture metadata, and reports.
// Field meanings are documented in docs/API.md; keep both in sync.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  // public profiles (name, avatar) so photo authors can be expanded;
  // emails stay hidden unless a user sets emailVisibility
  users.viewRule = "";
  app.save(users);

  const photos = new Collection({
    type: "base",
    name: "photos",
    listRule: "hidden = false || author = @request.auth.id",
    viewRule: "hidden = false || author = @request.auth.id",
    createRule: "@request.auth.id != '' && @request.body.author = @request.auth.id && @request.body.hidden:isset = false",
    updateRule: "author = @request.auth.id && (@request.body.author:isset = false || @request.body.author = @request.auth.id) && @request.body.hidden:isset = false",
    deleteRule: "author = @request.auth.id",
    fields: [
      { type: "relation", name: "author", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: true },
      {
        type: "file",
        name: "image",
        required: true,
        maxSelect: 1,
        maxSize: 31457280,
        mimeTypes: ["image/jpeg", "image/png", "image/webp"],
        thumbs: ["400x0", "1600x0"],
      },
      { type: "text", name: "title", max: 200 },
      { type: "select", name: "license", required: true, maxSelect: 1, values: ["arr", "cc-by", "cc-by-sa", "cc0"] },
      { type: "select", name: "platform", required: true, maxSelect: 1, values: ["ios", "android", "web"] },
      { type: "text", name: "device", max: 200 },
      { type: "date", name: "captured_at", required: true },

      // position, WGS84
      { type: "number", name: "lat", required: true, min: -90, max: 90 },
      { type: "number", name: "lon", required: true, min: -180, max: 180 },
      { type: "number", name: "gps_accuracy", min: 0 },
      { type: "number", name: "altitude" },
      { type: "number", name: "altitude_accuracy", min: 0 },

      // orientation of the camera's optical axis, degrees
      { type: "number", name: "heading", min: 0, max: 360 },
      { type: "number", name: "heading_accuracy", min: 0 },
      { type: "number", name: "pitch", min: -90, max: 90 },
      { type: "number", name: "roll", min: -180, max: 180 },

      // optics
      { type: "number", name: "focal_length_mm", min: 0 },
      { type: "number", name: "focal_length_35mm", min: 0 },
      { type: "number", name: "fov_h", min: 0, max: 360 },
      { type: "number", name: "fov_v", min: 0, max: 360 },
      { type: "number", name: "width", min: 0, onlyInt: true },
      { type: "number", name: "height", min: 0, onlyInt: true },

      // raw extras (sensor samples, calibration state, app version)
      { type: "json", name: "sensors", maxSize: 65536 },

      { type: "bool", name: "hidden" },
      { type: "autodate", name: "created", onCreate: true, onUpdate: false },
      { type: "autodate", name: "updated", onCreate: true, onUpdate: true },
    ],
    indexes: [
      "CREATE INDEX idx_photos_lat_lon ON photos (lat, lon)",
      "CREATE INDEX idx_photos_author ON photos (author)",
      "CREATE INDEX idx_photos_captured ON photos (captured_at)",
    ],
  });
  app.save(photos);

  const reports = new Collection({
    type: "base",
    name: "reports",
    listRule: null,
    viewRule: null,
    createRule: "@request.body.reporter = '' || @request.body.reporter = @request.auth.id",
    updateRule: null,
    deleteRule: null,
    fields: [
      { type: "relation", name: "photo", required: true, collectionId: photos.id, maxSelect: 1, cascadeDelete: true },
      { type: "relation", name: "reporter", collectionId: users.id, maxSelect: 1 },
      { type: "select", name: "reason", required: true, maxSelect: 1, values: ["person", "property", "abuse", "copyright", "other"] },
      { type: "text", name: "note", max: 2000 },
      { type: "autodate", name: "created", onCreate: true, onUpdate: false },
    ],
  });
  app.save(reports);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("reports"));
  app.delete(app.findCollectionByNameOrId("photos"));
  const users = app.findCollectionByNameOrId("users");
  users.viewRule = "id = @request.auth.id";
  app.save(users);
});
