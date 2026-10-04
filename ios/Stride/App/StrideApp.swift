import SwiftUI
import UIKit

@main
struct StrideApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var auth = AuthStore()
    @StateObject private var queue = UploadQueue.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(auth)
                .environmentObject(queue)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Reconnect to the background upload session as early as possible.
        UploadQueue.shared.activate()
        return true
    }

    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        UploadQueue.shared.activate()
        UploadQueue.shared.backgroundCompletionHandler = completionHandler
    }
}

struct RootView: View {
    @EnvironmentObject private var auth: AuthStore
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.scenePhase) private var scenePhase
    @State private var bootstrapped = false

    var body: some View {
        Group {
            if auth.isLoggedIn {
                MainTabView()
            } else {
                LoginView()
            }
        }
        .task {
            guard !bootstrapped else { return }
            bootstrapped = true
            queue.auth = auth
            await auth.refresh()
            queue.kick()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                queue.auth = auth
                queue.kick()
            }
        }
        .onChange(of: auth.token) { _, _ in
            queue.auth = auth
            queue.kick()
        }
    }
}

struct MainTabView: View {
    @EnvironmentObject private var queue: UploadQueue

    var body: some View {
        TabView {
            CameraView()
                .tabItem { Label("Camera", systemImage: "camera") }
            QueueView()
                .tabItem { Label("Uploads", systemImage: "icloud.and.arrow.up") }
                .badge(queue.pendingCount)
            MapScreen()
                .tabItem { Label("Map", systemImage: "map") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
    }
}
