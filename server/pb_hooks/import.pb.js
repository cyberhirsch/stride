/// <reference path="../pb_data/types.d.ts" />

// Console command: bulk-import photos prepared by tools/prepare_seed.py.
//
//   docker exec stride-pb /pb/pocketbase import-seed you@example.com [license] \
//     --dir=/pb_data --hooksDir=/pb/pb_hooks --migrationsDir=/pb/pb_migrations
//
// Reads $STRIDE_SEED_DIR/manifest.json (default /seed). Photos are owned by
// the Stride user with that email. Re-running skips photos already imported
// (matched on sensors.source_id).
$app.rootCmd.addCommand(new Command({
  use: "import-seed",
  short: "Import seed photos from manifest.json",
  run: (cmd, args) => {
    if (args.length < 1) throw new Error("usage: import-seed <user email> [arr|cc-by|cc-by-sa|cc0]");
    const license = args[1] || "arr";
    const dir = $os.getenv("STRIDE_SEED_DIR") || "/seed";
    const user = $app.findAuthRecordByEmail("users", args[0]);
    const items = JSON.parse(toString($os.readFile(dir + "/manifest.json")));
    const photos = $app.findCollectionByNameOrId("photos");

    let added = 0, skipped = 0, failed = 0;
    for (const m of items) {
      const seen = $app.findRecordsByFilter("photos", "sensors.source_id = {:s}", "", 1, 0, { s: m.source_id });
      if (seen.length) {
        skipped++;
        continue;
      }
      try {
        const r = new Record(photos);
        r.set("author", user.id);
        r.set("image", $filesystem.fileFromPath(dir + "/" + m.file));
        r.set("license", license);
        r.set("platform", "web");
        r.set("device", m.device || "");
        r.set("captured_at", m.captured_at);
        for (const k of ["lat", "lon", "gps_accuracy", "altitude", "heading", "focal_length_mm",
                         "focal_length_35mm", "fov_h", "fov_v", "width", "height"]) {
          if (m[k] !== undefined) r.set(k, m[k]);
        }
        r.set("has_heading", !!m.has_heading);
        r.set("has_tilt", false);
        r.set("sensors", { source: "immich", source_id: m.source_id, heading_ref: m.heading_ref || null });
        $app.save(r);
        added++;
      } catch (err) {
        failed++;
        console.log("failed", m.file, err);
      }
      if ((added + skipped + failed) % 100 === 0) console.log(`${added + skipped + failed}/${items.length}`);
    }
    console.log(`imported ${added}, already there ${skipped}, failed ${failed}`);
  },
}));
