import Foundation

/// Offline-first upload queue. Every capture is stored in Application Support
/// as `<id>.jpg` + `<id>_thumb.jpg` + `<id>.json` (sidecar) and uploaded with a
/// background URLSession upload task, so uploads continue when the app is
/// suspended and are retried on the next launch / foreground.
@MainActor
final class UploadQueue: ObservableObject {
    static let shared = UploadQueue()
    static let sessionIdentifier = "app.stride.ios.upload"

    @Published private(set) var records: [CaptureRecord] = []

    weak var auth: AuthStore?
    var backgroundCompletionHandler: (() -> Void)?

    private let sessionDelegate = UploadSessionDelegate()
    private var session: URLSession?
    private var inFlight: Set<String> = []
    private let directory: URL
    private let bodyDirectory: URL

    init() {
        let fm = FileManager.default
        let support = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        directory = support.appendingPathComponent("Captures", isDirectory: true)
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        bodyDirectory = caches.appendingPathComponent("UploadBodies", isDirectory: true)
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true)
        try? fm.createDirectory(at: bodyDirectory, withIntermediateDirectories: true)
        loadRecords()
    }

    /// Creates (or reconnects to) the background session. Safe to call repeatedly.
    func activate() {
        guard session == nil else { return }
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.timeoutIntervalForResource = 60 * 60 * 24 * 3
        sessionDelegate.owner = self
        session = URLSession(configuration: config, delegate: sessionDelegate, delegateQueue: nil)
    }

    // MARK: Files

    func imageURL(_ id: String) -> URL { directory.appendingPathComponent("\(id).jpg") }
    func thumbnailURL(_ id: String) -> URL { directory.appendingPathComponent("\(id)_thumb.jpg") }
    private func sidecarURL(_ id: String) -> URL { directory.appendingPathComponent("\(id).json") }
    private func bodyURL(_ id: String) -> URL { bodyDirectory.appendingPathComponent("\(id).multipart") }

    private func loadRecords() {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: directory.path) else { return }
        let decoder = JSONDecoder()
        var loaded: [CaptureRecord] = []
        for name in names where name.hasSuffix(".json") {
            let url = directory.appendingPathComponent(name)
            if let data = try? Data(contentsOf: url), let record = try? decoder.decode(CaptureRecord.self, from: data) {
                loaded.append(record)
            }
        }
        records = loaded.sorted { $0.createdAt > $1.createdAt }
    }

    private func persist(_ record: CaptureRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        try? data.write(to: sidecarURL(record.id), options: .atomic)
    }

    private func update(_ id: String, _ change: (inout CaptureRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        change(&records[index])
        persist(records[index])
    }

    // MARK: Adding

    /// Processes a captured JPEG (metadata, upright pixels) off the main
    /// thread, stores it, and starts the upload.
    func addCapture(photoData: Data, input: CaptureInput, title: String, license: License,
                    sensorsJSON: String) async throws {
        let processed = try await Task.detached(priority: .userInitiated) {
            try PhotoProcessor.process(photoData, input: input)
        }.value

        let id = UUID().uuidString
        try processed.jpeg.write(to: imageURL(id), options: .atomic)
        if let thumb = processed.thumbnail {
            try? thumb.write(to: thumbnailURL(id), options: .atomic)
        }

        let fields = UploadFields(
            title: title,
            license: license.rawValue,
            device: AppConfig.deviceName,
            capturedAt: input.capturedAt,
            lat: input.latitude,
            lon: input.longitude,
            gpsAccuracy: input.horizontalAccuracy,
            altitude: input.ellipsoidalAltitude,
            altitudeAccuracy: input.verticalAccuracy,
            heading: input.heading,
            headingAccuracy: input.headingAccuracy,
            pitch: input.pitch,
            roll: input.roll,
            focalLengthMm: processed.focalLengthMm,
            focalLength35mm: processed.focalLength35mm,
            fovH: processed.fovH,
            fovV: processed.fovV,
            width: processed.width,
            height: processed.height,
            sensorsJSON: sensorsJSON
        )
        let record = CaptureRecord(id: id, createdAt: Date(), status: .pending, attempts: 0,
                                   lastError: nil, remoteId: nil, fields: fields)
        persist(record)
        records.insert(record, at: 0)
        kick()
    }

    // MARK: Uploading

    var pendingCount: Int {
        records.filter { $0.status == .pending || $0.status == .uploading }.count
    }

    /// Starts uploads for every pending record that has no live task.
    func kick() {
        activate()
        guard let session, let token = auth?.token, let userId = auth?.user?.id else { return }
        session.getAllTasks { tasks in
            let live = Set(tasks.filter { $0.state == .running || $0.state == .suspended }
                .compactMap { $0.taskDescription })
            Task { @MainActor in
                self.startMissing(live: live, token: token, userId: userId)
            }
        }
    }

    private func startMissing(live: Set<String>, token: String, userId: String) {
        for record in records where record.status == .pending || record.status == .uploading {
            if live.contains(record.id) || inFlight.contains(record.id) { continue }
            start(record, token: token, userId: userId)
        }
    }

    private func start(_ record: CaptureRecord, token: String, userId: String) {
        guard let session else { return }
        let id = record.id
        inFlight.insert(id)
        update(id) { $0.status = .uploading }

        let fields = record.fields.formFields(authorId: userId)
        let image = imageURL(id)
        let body = bodyURL(id)
        let boundary = "StrideBoundary-\(UUID().uuidString)"
        var request = URLRequest(url: AppConfig.serverURL.appendingPathComponent("api/collections/photos/records"))
        request.httpMethod = "POST"
        request.setValue(token, forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let finalRequest = request

        Task {
            do {
                try await Task.detached(priority: .utility) {
                    try Multipart.writeBody(to: body, boundary: boundary, fields: fields, fileField: "image",
                                            fileName: "\(id).jpg", mimeType: "image/jpeg", fileURL: image)
                }.value
                let task = session.uploadTask(with: finalRequest, fromFile: body)
                task.taskDescription = id
                task.resume()
            } catch {
                self.inFlight.remove(id)
                self.update(id) {
                    $0.status = .failed
                    $0.lastError = "Could not prepare upload: \(error.localizedDescription)"
                }
            }
        }
    }

    /// Called from the session delegate when a task finishes.
    fileprivate func taskFinished(recordId: String?, status: Int?, body: Data?, errorText: String?) {
        guard let id = recordId else { return }
        inFlight.remove(id)
        try? FileManager.default.removeItem(at: bodyURL(id))
        guard records.contains(where: { $0.id == id }) else { return }

        if let errorText {
            update(id) {
                $0.status = .pending
                $0.attempts += 1
                $0.lastError = errorText
            }
            return
        }
        let code = status ?? 0
        if (200..<300).contains(code) {
            var remoteId: String?
            if let body, let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
                remoteId = obj["id"] as? String
            }
            update(id) {
                $0.status = .uploaded
                $0.remoteId = remoteId
                $0.lastError = nil
            }
            return
        }
        let message = APIClient.error(status: code, body: body).message
        update(id) {
            $0.attempts += 1
            $0.lastError = message
            // 401: log in again, then it retries. 5xx: server trouble, retry later.
            // Other 4xx: the server rejected the data; needs a manual retry.
            if code == 401 || code >= 500 || code == 0 {
                $0.status = .pending
            } else {
                $0.status = .failed
            }
        }
    }

    fileprivate func finishBackgroundEvents() {
        let handler = backgroundCompletionHandler
        backgroundCompletionHandler = nil
        handler?()
    }

    // MARK: User actions

    func retry(_ id: String) {
        update(id) {
            $0.status = .pending
            $0.lastError = nil
        }
        kick()
    }

    func retryAll() {
        for record in records where record.status == .failed {
            update(record.id) { $0.status = .pending }
        }
        kick()
    }

    func remove(_ id: String) {
        session?.getAllTasks { tasks in
            for task in tasks where task.taskDescription == id { task.cancel() }
        }
        inFlight.remove(id)
        let fm = FileManager.default
        try? fm.removeItem(at: imageURL(id))
        try? fm.removeItem(at: thumbnailURL(id))
        try? fm.removeItem(at: sidecarURL(id))
        try? fm.removeItem(at: bodyURL(id))
        records.removeAll { $0.id == id }
    }

    func clearUploaded() {
        for record in records where record.status == .uploaded {
            remove(record.id)
        }
    }
}

/// URLSession delegate for the background session. Runs on the session's
/// serial delegate queue and forwards results to the main actor.
final class UploadSessionDelegate: NSObject, URLSessionDataDelegate {
    weak var owner: UploadQueue?
    private var buffers: [Int: Data] = [:]
    private let lock = NSLock()

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        buffers[dataTask.taskIdentifier, default: Data()].append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let body = buffers.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        let status = (task.response as? HTTPURLResponse)?.statusCode
        let recordId = task.taskDescription
        let errorText: String?
        if let error {
            let ns = error as NSError
            if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled {
                errorText = "Cancelled"
            } else {
                errorText = error.localizedDescription
            }
        } else {
            errorText = nil
        }
        let owner = self.owner
        Task { @MainActor in
            owner?.taskFinished(recordId: recordId, status: status, body: body, errorText: errorText)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let owner = self.owner
        Task { @MainActor in
            owner?.finishBackgroundEvents()
        }
    }
}
