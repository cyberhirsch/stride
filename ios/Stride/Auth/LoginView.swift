import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var auth: AuthStore

    private enum Mode: String, CaseIterable, Identifiable {
        case login = "Log in"
        case register = "Register"
        var id: String { rawValue }
    }

    @State private var mode: Mode = .login
    @State private var email = ""
    @State private var password = ""
    @State private var name = ""
    @State private var busy = false
    @State private var errorText: String?
    @State private var showServer = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Mode", selection: $mode) {
                        ForEach(Mode.allCases) { m in Text(m.rawValue).tag(m) }
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    TextField("Email", text: $email)
                        .keyboardType(.emailAddress)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $password)
                        .textContentType(mode == .login ? .password : .newPassword)
                    if mode == .register {
                        TextField("Name", text: $name)
                            .textContentType(.name)
                    }
                }
                if let errorText {
                    Section {
                        Text(errorText).foregroundStyle(.red).font(.footnote)
                    }
                }
                Section {
                    Button(action: submit) {
                        HStack {
                            Spacer()
                            if busy { ProgressView() } else { Text(mode.rawValue).bold() }
                            Spacer()
                        }
                    }
                    .disabled(busy || email.isEmpty || password.isEmpty)
                }
                Section {
                    Button("Server: \(AppConfig.serverURL.absoluteString)") { showServer = true }
                        .font(.footnote)
                }
            }
            .navigationTitle("Stride")
            .sheet(isPresented: $showServer) {
                NavigationStack {
                    Form { ServerSettingsSection() }
                        .navigationTitle("Server")
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { showServer = false }
                            }
                        }
                }
            }
        }
    }

    private func submit() {
        busy = true
        errorText = nil
        let mail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let pass = password
        let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let currentMode = mode
        Task {
            defer { busy = false }
            do {
                if currentMode == .login {
                    try await auth.login(email: mail, password: pass)
                } else {
                    try await auth.register(email: mail, password: pass, name: displayName)
                }
            } catch {
                errorText = error.localizedDescription
            }
        }
    }
}
