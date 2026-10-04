/// <reference path="../pb_data/types.d.ts" />

// Volunteer alignment workers (SETI@home style): any signed-in user can
// pull a job, align it on their machine and post the result. Logic lives in
// stride/lib.js.

routerAdd("POST", "/api/stride/jobs/claim", (e) => {
  const job = require(`${__hooks}/stride/lib.js`).claim(e.app, e.auth);
  return job ? e.json(200, job) : e.noContent(204);
}, $apis.requireAuth("users"));

routerAdd("POST", "/api/stride/jobs/{id}/heartbeat", (e) => {
  return e.json(200, require(`${__hooks}/stride/lib.js`).heartbeat(e.app, e.request.pathValue("id"), e.auth));
}, $apis.requireAuth("users"));

routerAdd("POST", "/api/stride/jobs/{id}/fail", (e) => {
  const body = e.requestInfo().body;
  return e.json(200, require(`${__hooks}/stride/lib.js`).fail(e.app, e.request.pathValue("id"), e.auth, body.error));
}, $apis.requireAuth("users"));

// multipart: `result` (JSON string) + optional `points` file
routerAdd("POST", "/api/stride/jobs/{id}/result", (e) => {
  const body = e.requestInfo().body;
  let result;
  try {
    result = JSON.parse(body.result);
  } catch (_) {
    throw new BadRequestError("result must be a JSON string");
  }
  const files = e.findUploadedFiles("points");
  return e.json(200, require(`${__hooks}/stride/lib.js`).submit(
    e.app, e.request.pathValue("id"), e.auth, result, files && files.length ? files[0] : null));
}, $apis.requireAuth("users"), $apis.bodyLimit(16 << 20));

// group photos into places and queue alignment
cronAdd("stride-cluster", "*/10 * * * *", () => {
  const res = require(`${__hooks}/stride/lib.js`).cluster($app);
  if (res.queued) console.log("stride-cluster", JSON.stringify(res));
});

$app.rootCmd.addCommand(new Command({
  use: "stride-cluster",
  short: "Cluster photos into places and queue alignment jobs now",
  run: () => console.log(JSON.stringify(require(`${__hooks}/stride/lib.js`).cluster($app))),
}));

$app.rootCmd.addCommand(new Command({
  use: "stride-approve",
  short: "Apply an alignment result that awaits review: stride-approve <job id>",
  run: (cmd, args) => {
    require(`${__hooks}/stride/lib.js`).approve($app, args[0]);
    console.log("applied", args[0]);
  },
}));
