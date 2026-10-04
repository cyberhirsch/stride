import Foundation

enum Prefs {
    static let defaultLicense = "defaultLicense"
    static let serverURLOverride = "serverURLOverride"
}

enum AppConfig {
    static let fallbackServerURL = "http://192.168.178.66:8091"

    /// Value of the STRIDE_API_URL build setting (Info.plist key StrideAPIURL).
    static var bundledServerURL: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "StrideAPIURL") as? String) ?? ""
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty || value.hasPrefix("$(") { return fallbackServerURL }
        return value
    }

    static var serverURLOverride: String {
        (UserDefaults.standard.string(forKey: Prefs.serverURLOverride) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Effective server base URL, without trailing slash.
    static var serverURL: URL {
        let override = serverURLOverride
        var text = override.isEmpty ? bundledServerURL : override
        while text.hasSuffix("/") { text.removeLast() }
        return URL(string: text) ?? URL(string: fallbackServerURL)!
    }

    static func isValidServerURL(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), url.host != nil else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }

    static var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return "\(version) (\(build))"
    }

    /// Hardware model identifier such as "iPhone16,1".
    static var modelIdentifier: String {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"], !sim.isEmpty {
            return sim
        }
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw -> String in
            let bytes = raw.prefix(while: { $0 != 0 })
            return String(decoding: bytes, as: UTF8.self)
        }
        return machine.isEmpty ? "iPhone" : machine
    }

    /// Value for the `device` form field.
    static var deviceName: String { "Apple \(modelIdentifier)" }
}

enum License: String, CaseIterable, Identifiable, Codable {
    case arr = "arr"
    case ccBy = "cc-by"
    case ccBySa = "cc-by-sa"
    case cc0 = "cc0"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .arr: return "All rights reserved"
        case .ccBy: return "CC BY"
        case .ccBySa: return "CC BY-SA"
        case .cc0: return "CC0"
        }
    }

    static func displayName(for raw: String) -> String {
        License(rawValue: raw)?.label ?? raw
    }
}

enum ReportReason: String, CaseIterable, Identifiable {
    case person, property, abuse, copyright, other

    var id: String { rawValue }

    var label: String {
        switch self {
        case .person: return "Shows an identifiable person"
        case .property: return "Private property"
        case .abuse: return "Abusive or offensive"
        case .copyright: return "Copyright"
        case .other: return "Other"
        }
    }
}
