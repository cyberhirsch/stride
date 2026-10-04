import Foundation

enum UploadStatus: String, Codable {
    case pending
    case uploading
    case uploaded
    case failed

    var label: String {
        switch self {
        case .pending: return "Waiting"
        case .uploading: return "Uploading"
        case .uploaded: return "Uploaded"
        case .failed: return "Failed"
        }
    }
}

/// Form fields for `POST /api/collections/photos/records` (docs/API.md).
struct UploadFields: Codable {
    var title: String
    var license: String
    var device: String
    var capturedAt: Date
    var lat: Double
    var lon: Double
    var gpsAccuracy: Double?
    var altitude: Double?
    var altitudeAccuracy: Double?
    var heading: Double?
    var headingAccuracy: Double?
    var pitch: Double?
    var roll: Double?
    var focalLengthMm: Double?
    var focalLength35mm: Double?
    var fovH: Double?
    var fovV: Double?
    var width: Int
    var height: Int
    var sensorsJSON: String

    var hasHeading: Bool { heading != nil }
    var hasTilt: Bool { pitch != nil && roll != nil }

    /// Multipart text parts. `author` is the uploading user.
    func formFields(authorId: String) -> [(String, String)] {
        var out: [(String, String)] = [
            ("author", authorId),
            ("license", license),
            ("platform", "ios"),
            ("device", device),
            ("captured_at", PBDate.format(capturedAt)),
            ("lat", Self.number(lat, 8)),
            ("lon", Self.number(lon, 8)),
            ("width", String(width)),
            ("height", String(height)),
            ("has_heading", hasHeading ? "true" : "false"),
            ("has_tilt", hasTilt ? "true" : "false"),
            ("sensors", sensorsJSON),
        ]
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedTitle.isEmpty { out.append(("title", String(trimmedTitle.prefix(200)))) }

        func add(_ key: String, _ value: Double?, _ digits: Int, nonNegative: Bool = false) {
            guard let value, value.isFinite else { return }
            if nonNegative && value < 0 { return }
            out.append((key, Self.number(value, digits)))
        }
        add("gps_accuracy", gpsAccuracy, 2, nonNegative: true)
        add("altitude", altitude, 2)
        add("altitude_accuracy", altitudeAccuracy, 2, nonNegative: true)
        if let heading {
            // Schema range is 0...360; keep it in 0..<360.
            add("heading", OrientationMath.normalizeDegrees(heading), 3)
        }
        add("heading_accuracy", headingAccuracy, 2, nonNegative: true)
        if let pitch { add("pitch", OrientationMath.clamp(pitch, -90, 90), 3) }
        if let roll { add("roll", OrientationMath.clamp(roll, -180, 180), 3) }
        add("focal_length_mm", focalLengthMm, 3, nonNegative: true)
        add("focal_length_35mm", focalLength35mm, 1, nonNegative: true)
        add("fov_h", fovH, 3, nonNegative: true)
        add("fov_v", fovV, 3, nonNegative: true)
        return out
    }

    static func number(_ v: Double, _ digits: Int) -> String {
        String(format: "%.\(digits)f", v)
    }
}

struct CaptureRecord: Codable, Identifiable {
    let id: String
    let createdAt: Date
    var status: UploadStatus
    var attempts: Int
    var lastError: String?
    var remoteId: String?
    var fields: UploadFields
}

enum Multipart {
    /// Writes a multipart/form-data body to `bodyURL` (background upload tasks
    /// need a file).
    static func writeBody(to bodyURL: URL, boundary: String, fields: [(String, String)],
                          fileField: String, fileName: String, mimeType: String, fileURL: URL) throws {
        var data = Data()
        func append(_ s: String) { data.append(Data(s.utf8)) }
        for (name, value) in fields {
            append("--\(boundary)\r\n")
            append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
            append(value)
            append("\r\n")
        }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(fileName)\"\r\n")
        append("Content-Type: \(mimeType)\r\n\r\n")
        data.append(try Data(contentsOf: fileURL))
        append("\r\n--\(boundary)--\r\n")
        try FileManager.default.createDirectory(at: bodyURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: bodyURL, options: .atomic)
    }
}
