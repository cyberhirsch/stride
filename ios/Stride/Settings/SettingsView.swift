import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var auth: AuthStore
    @AppStorage(Prefs.defaultLicense) private var licenseRaw: String = License.ccBy.rawValue

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    if let user = auth.user {
                        LabeledContent("Name", value: user.name ?? "")
                        LabeledContent("Email", value: user.email ?? "")
                    }
                    Button("Log out", role: .destructive) { auth.logout() }
                }
                Section {
                    Picker("Default license", selection: $licenseRaw) {
                        ForEach(License.allCases) { l in
                            Text(l.label).tag(l.rawValue)
                        }
                    }
                } header: {
                    Text("Capture")
                } footer: {
                    Text("Used for new photos. You can change it per photo on the camera screen.")
                }
                ServerSettingsSection()
                Section("About") {
                    LabeledContent("Version", value: AppConfig.appVersion)
                    LabeledContent("Device", value: AppConfig.modelIdentifier)
                }
            }
            .navigationTitle("Settings")
        }
    }
}

/// Server URL override, shared by Settings and the login screen.
struct ServerSettingsSection: View {
    @AppStorage(Prefs.serverURLOverride) private var overrideURL: String = ""
    @State private var draft = ""
    @State private var invalid = false

    var body: some View {
        Section {
            TextField(AppConfig.bundledServerURL, text: $draft)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                Button("Save") { save() }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines) == overrideURL)
                Spacer()
                Button("Use default", role: .destructive) {
                    overrideURL = ""
                    draft = ""
                    invalid = false
                }
                .disabled(overrideURL.isEmpty)
            }
            .buttonStyle(.borderless)
            if invalid {
                Text("Enter a full http:// or https:// URL.").foregroundStyle(.red).font(.footnote)
            }
        } header: {
            Text("Server")
        } footer: {
            Text("In use: \(AppConfig.serverURL.absoluteString)\nBuilt-in default: \(AppConfig.bundledServerURL)\nLog out and in again after switching servers.")
        }
        .onAppear { draft = overrideURL }
    }

    private func save() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            overrideURL = ""
            invalid = false
            return
        }
        guard AppConfig.isValidServerURL(text) else {
            invalid = true
            return
        }
        invalid = false
        overrideURL = text
    }
}
