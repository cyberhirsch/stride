import Foundation
import Security

enum Keychain {
    static let service = "app.stride.ios"

    private static func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    @discardableResult
    static func save(_ data: Data, account: String) -> Bool {
        let query = baseQuery(account)
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func load(account: String) -> Data? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    static func delete(account: String) {
        SecItemDelete(baseQuery(account) as CFDictionary)
    }
}

private struct StoredAuth: Codable {
    let token: String
    let user: UserRecord
}

/// Session state for the `users` auth collection. The token lives in the Keychain.
@MainActor
final class AuthStore: ObservableObject {
    @Published private(set) var token: String?
    @Published private(set) var user: UserRecord?

    private static let account = "auth"

    init() {
        if let data = Keychain.load(account: Self.account),
           let stored = try? JSONDecoder().decode(StoredAuth.self, from: data) {
            token = stored.token
            user = stored.user
        }
    }

    var isLoggedIn: Bool { token != nil && user != nil }

    var client: APIClient { APIClient(baseURL: AppConfig.serverURL, token: token) }

    func login(email: String, password: String) async throws {
        let response = try await APIClient(token: nil).login(email: email, password: password)
        store(response)
    }

    func register(email: String, password: String, name: String) async throws {
        try await APIClient(token: nil).register(email: email, password: password, name: name)
        try await login(email: email, password: password)
    }

    /// Refreshes the token. Logs out on 401/403/404; keeps the session when offline.
    func refresh() async {
        guard token != nil else { return }
        do {
            let response = try await client.refresh()
            store(response)
        } catch let error as APIError where error.status == 401 || error.status == 403 || error.status == 404 {
            logout()
        } catch {
            // Offline or server unreachable: keep the stored session so capture works.
        }
    }

    func logout() {
        token = nil
        user = nil
        Keychain.delete(account: Self.account)
    }

    private func store(_ response: AuthResponse) {
        token = response.token
        user = response.record
        if let data = try? JSONEncoder().encode(StoredAuth(token: response.token, user: response.record)) {
            Keychain.save(data, account: Self.account)
        }
    }
}
