/// <reference path="../pb_data/types.d.ts" />

// Hide a photo from public listings once it collects 3 reports.
// A superuser reviews it in the dashboard and clears `hidden` or deletes it.
onRecordAfterCreateSuccess((e) => {
  const photoId = e.record.get("photo");
  const count = e.app.countRecords("reports", $dbx.hashExp({ photo: photoId }));
  if (count >= 3) {
    const photo = e.app.findRecordById("photos", photoId);
    if (!photo.getBool("hidden")) {
      photo.set("hidden", true);
      e.app.save(photo);
    }
  }
  e.next();
}, "reports");
