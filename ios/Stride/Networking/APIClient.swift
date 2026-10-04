import Foundation

struct APIError: LocalizedError {
    let status: Int
    let message: String

    var errorDescription: String? { message }
}

struct BoundingBox: Equatable {
    var south: Double
    var west: Double
    var north: Double
    var east: Double
}

/// Thin client for the PocketBase REST API (docs/API.md).
struct APIClient {
    let baseURL: URL
    let token: String?

    init(baseURL: URL = AppConfig.serverURL, token: String?) {
        self.baseURL = baseURL
        self.token = token
    }

    // MARK: URL building

    /// RFC 3986 unreserved characters only, so '&', '=', '+', '|' etc. in
    /// filter expressions are always escaped.
    static func encode(_ s: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    func url(_ path: String, query: [(String, String)] = []) -> URL {
        let full = baseURL.appendingPathComponent(path)
        guard !query.isEmpty, var comps = URLComponents(url: full, resolvingAgainstBaseURL: false) else {
            return full
        }
        comps.percentEncodedQuery = query.map { "\(Self.encode($0.0))=\(Self.encode($0.1))" }.joined(separator: "&")
        return comps.url ?? full
    }

    func fileURL(recordId: String, fileName: String, thumb: String? = nil) -> URL {
        let path = "api/files/photos/\(Self.encode(recordId))/\(Self.encode(fileName))"
        var text = baseURL.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        text += "/" + path
        if let thumb { text += "?thumb=" + Self.encode(thumb) }
        return URL(string: text) ?? baseURL
    }

    func makeRequest(_ method: String, _ path: String, query: [(String, String)] = [], json: [String: String]? = nil) throws -> URLRequest {
        var req = URLRequest(url: url(path, query: query))
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token, !token.isEmpty {
            req.setValue(token, forHTTPHeaderField: "Authorization")
        }
        if let json {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(json)
        }
        return req
    }

    func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let data = try await sendRaw(request)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError(status: 0, message: "Unexpected server response.")
        }
    }

    @discardableResult
    func sendRaw(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw Self.error(status: status, body: data)
        }
        return data
    }

    static func error(status: Int, body: Data?) -> APIError {
        if let body, let parsed = try? JSONDecoder().decode(PBErrorBody.self, from: body) {
            let text = parsed.readable
            if !text.isEmpty { return APIError(status: status, message: text) }
        }
        return APIError(status: status, message: "Server error (HTTP \(status)).")
    }

    // MARK: Auth

    func login(email: String, password: String) async throws -> AuthResponse {
        let req = try makeRequest("POST", "api/collections/users/auth-with-password",
                                  json: ["identity": email, "password": password])
        return try await send(req, as: AuthResponse.self)
    }

    func register(email: String, password: String, name: String) async throws {
        let req = try makeRequest("POST", "api/collections/users/records",
                                  json: ["email": email, "password": password, "passwordConfirm": password, "name": name])
        try await sendRaw(req)
    }

    func refresh() async throws -> AuthResponse {
        let req = try makeRequest("POST", "api/collections/users/auth-refresh")
        return try await send(req, as: AuthResponse.self)
    }

    // MARK: Photos

    static func filter(for box: BoundingBox) -> String {
        let f = { (v: Double) in String(format: "%.6f", v) }
        let latPart = "lat>=\(f(box.south)) && lat<=\(f(box.north))"
        if box.west <= box.east {
            return "(\(latPart) && lon>=\(f(box.west)) && lon<=\(f(box.east)))"
        }
        // Box crosses the antimeridian.
        return "(\(latPart) && (lon>=\(f(box.west)) || lon<=\(f(box.east))))"
    }

    func photos(in box: BoundingBox) async throws -> [PhotoSummary] {
        let query: [(String, String)] = [
            ("filter", Self.filter(for: box)),
            ("sort", "-captured_at"),
            ("perPage", "500"),
            ("skipTotal", "1"),
            ("fields", "id,collectionId,image,lat,lon,heading,has_heading,fov_h,captured_at,title"),
        ]
        let req = try makeRequest("GET", "api/collections/photos/records", query: query)
        return try await send(req, as: ListResponse<PhotoSummary>.self).items
    }

    func photo(id: String) async throws -> PhotoDetail {
        let req = try makeRequest("GET", "api/collections/photos/records/\(Self.encode(id))",
                                  query: [("expand", "author")])
        return try await send(req, as: PhotoDetail.self)
    }

    func report(photoId: String, reason: ReportReason, note: String, reporterId: String?) async throws {
        var body = ["photo": photoId, "reason": reason.rawValue, "note": note]
        if let reporterId, !reporterId.isEmpty { body["reporter"] = reporterId }
        let req = try makeRequest("POST", "api/collections/reports/records", json: body)
        try await sendRaw(req)
    }
}
