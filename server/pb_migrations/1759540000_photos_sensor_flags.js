/// <reference path="../pb_data/types.d.ts" />

// PocketBase stores absent numbers as 0, so flag whether heading and
// pitch/roll were actually measured (e.g. web uploads often lack them).
migrate((app) => {
  const photos = app.findCollectionByNameOrId("photos");
  photos.fields.add(new BoolField({ name: "has_heading" }));
  photos.fields.add(new BoolField({ name: "has_tilt" }));
  app.save(photos);
}, (app) => {
  const photos = app.findCollectionByNameOrId("photos");
  photos.fields.removeByName("has_heading");
  photos.fields.removeByName("has_tilt");
  app.save(photos);
});
