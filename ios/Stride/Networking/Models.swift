import Foundation
import CoreLocation

struct UserRecord: Codable, Equatable {
    let id: String
    var email: String?
    var name: String?
}

struct AuthResponse: Decodable {
    let token: String
    let record: UserRecord
}

struct ListResponse<T: Decodable>: Decodable {
    let items: [T]
}

/// Map list item (fields= subset from the bbox query).
struct PhotoSummary: Decodable, Identifiable, Equatable {
    let id: String
    let collectionId: String?
    let image: String
    let lat: Double
    let lon: Double
    let heading: Double?
    let hasHeading: Bool?
    let fovH: Double?
    let capturedAt: String?
    let title: String?

    enum CodingKeys: String, CodingKey {
        case id, collectionId, image, lat, lon, heading, title
        case hasHeading = "has_heading"
        case fovH = "fov_h"
        case capturedAt = "captured_at"
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Heading only when it was actually measured (PocketBase stores absent numbers as 0).
    var measuredHeading: Double? {
        guard hasHeading == true, let heading else { return nil }
        return heading
    }
}

struct PhotoDetail: Decodable {
    let id: String
    let image: String
    let author: String?
    let title: String?
    let license: String?
    let platform: String?
    let device: String?
    let capturedAt: String?
    let lat: Double?
    let lon: Double?
    let gpsAccuracy: Double?
    let heading: Double?
    let headingAccuracy: Double?
    let hasHeading: Bool?
    let hasTilt: Bool?
    let pitch: Double?
    let roll: Double?
    let fovH: Double?
    let fovV: Double?
    let focalLength35mm: Double?
    let width: Double?
    let height: Double?
    let expand: Expand?

    struct Expand: Decodable {
        let author: UserRecord?
    }

    enum CodingKeys: String, CodingKey {
        case id, image, author, title, license, platform, device, lat, lon, heading, pitch, roll, width, height, expand
        case capturedAt = "captured_at"
        case gpsAccuracy = "gps_accuracy"
        case headingAccuracy = "heading_accuracy"
        case hasHeading = "has_heading"
        case hasTilt = "has_tilt"
        case fovH = "fov_h"
        case fovV = "fov_v"
        case focalLength35mm = "focal_length_35mm"
    }

    var authorName: String? {
        guard let user = expand?.author else { return nil }
        if let name = user.name, !name.isEmpty { return name }
        return nil
    }
}

/// PocketBase error body: {status, message, data: {field: {code, message}}}.
struct PBErrorBody: Decodable {
    struct FieldError: Decodable {
        let code: String?
        let message: String?
    }

    let status: Int?
    let message: String?
    let data: [String: FieldError]?

    var readable: String {
        var parts: [String] = []
        if let message, !message.isEmpty { parts.append(message) }
        if let data {
            for key in data.keys.sorted() {
                if let m = data[key]?.message { parts.append("\(key): \(m)") }
            }
        }
        return parts.joined(separator: "\n")
    }
}

enum PBDate {
    /// PocketBase returns "2026-10-04 09:12:33.123Z"; also accepts RFC 3339.
    static func parse(_ text: String?) -> Date? {
        guard var s = text?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        s = s.replacingOccurrences(of: " ", with: "T")
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = withFraction.date(from: s) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: s)
    }

    /// ISO 8601 UTC with milliseconds, e.g. 2026-10-04T09:12:33.123Z.
    static func format(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(secondsFromGMT: 0)
        return f.string(from: date)
    }
}
