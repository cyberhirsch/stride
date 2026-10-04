import SwiftUI
import CoreLocation

struct CameraView: View {
    @EnvironmentObject private var queue: UploadQueue
    @StateObject private var camera = CameraController()
    @StateObject private var sensors = SensorManager()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(Prefs.defaultLicense) private var licenseRaw: String = License.ccBy.rawValue
    @State private var title = ""
    @State private var message: String?
    @State private var flash = false
    @State private var visible = false
    @State private var saving = false

    private var license: License { License(rawValue: licenseRaw) ?? .ccBy }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if camera.isAuthorized {
                CameraPreview(session: camera.session)
                    .ignoresSafeArea(edges: .horizontal)
            } else if camera.authorizationChecked {
                VStack(spacing: 12) {
                    Image(systemName: "camera.fill").font(.largeTitle)
                    Text("Camera access is needed to take photos.")
                    Text("Enable it in the Settings app.").font(.footnote)
                }
                .foregroundStyle(.white)
                .padding()
            }
            if flash {
                Color.white.ignoresSafeArea().transition(.opacity)
            }
            VStack(spacing: 8) {
                hud
                Spacer()
                controls
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .onAppear {
            visible = true
            startAll()
        }
        .onDisappear {
            visible = false
            stopAll()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active && visible {
                startAll()
            } else if phase == .background {
                stopAll()
            }
        }
    }

    // MARK: HUD

    private var hud: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                hudItem("GPS", gpsText, color: gpsColor)
                hudItem("Heading", headingText, color: headingColor)
                hudItem("Pitch", angleText(sensors.pose?.pitch), color: .white)
                hudItem("Roll", angleText(sensors.pose?.roll), color: .white)
            }
            if needsCalibration {
                warning("Compass accuracy is poor (\(accuracyText)). Move the phone in a figure-8 to calibrate.")
            }
            if sensors.headingSource == .magneticUncorrected {
                warning("Heading is magnetic, not true north. Waiting for location to correct it.")
            }
            if sensors.reducedAccuracy {
                warning("Precise location is off. Enable it for Stride in Settings.")
            }
            if sensors.authorization == .denied || sensors.authorization == .restricted {
                warning("Location access is off. Photos need a GPS position.")
            }
            if let message {
                warning(message)
            }
        }
        .padding(8)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
        .padding(.top, 4)
    }

    private func hudItem(_ label: String, _ value: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.white.opacity(0.7))
            Text(value).font(.system(.footnote, design: .monospaced)).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func warning(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.caption)
            .foregroundStyle(.yellow)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var gpsText: String {
        guard let loc = sensors.location else { return "no fix" }
        return String(format: "±%.0f m", loc.horizontalAccuracy)
    }

    private var gpsColor: Color {
        guard let acc = sensors.location?.horizontalAccuracy else { return .red }
        if acc <= 10 { return .green }
        if acc <= 30 { return .yellow }
        return .red
    }

    private var headingText: String {
        guard let pose = sensors.pose, sensors.headingSource != .unavailable else { return "—" }
        let suffix = sensors.headingSource.isTrue ? "T" : "M"
        return String(format: "%.0f°", pose.heading) + suffix
    }

    private var accuracyText: String {
        sensors.headingAccuracy < 0 ? "unknown" : String(format: "±%.0f°", sensors.headingAccuracy)
    }

    private var needsCalibration: Bool {
        sensors.headingAccuracy < 0 || sensors.headingAccuracy > 20
    }

    private var headingColor: Color {
        needsCalibration ? .yellow : .green
    }

    private func angleText(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%+.0f°", value)
    }

    // MARK: Controls

    private var controls: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                TextField("Title (optional)", text: $title)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.done)
                Picker("License", selection: $licenseRaw) {
                    ForEach(License.allCases) { l in
                        Text(l.label).tag(l.rawValue)
                    }
                }
                .pickerStyle(.menu)
                .tint(.white)
                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))
            }
            HStack {
                queueBadge
                    .frame(maxWidth: .infinity, alignment: .leading)
                shutterButton
                Text(accuracyText)
                    .font(.caption.monospaced())
                    .foregroundStyle(headingColor)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private var queueBadge: some View {
        let count = queue.pendingCount
        return Label(count == 0 ? "All uploaded" : "\(count) to upload",
                     systemImage: count == 0 ? "checkmark.icloud" : "icloud.and.arrow.up")
            .font(.caption)
            .foregroundStyle(.white)
    }

    private var canShoot: Bool {
        camera.isRunning && !camera.isCapturing && !saving && sensors.location != nil
    }

    private var shutterButton: some View {
        Button(action: shoot) {
            ZStack {
                Circle().stroke(.white, lineWidth: 4).frame(width: 74, height: 74)
                Circle().fill(canShoot ? Color.white : Color.gray).frame(width: 62, height: 62)
                if saving || camera.isCapturing {
                    ProgressView().tint(.black)
                }
            }
        }
        .disabled(!canShoot)
        .accessibilityLabel("Take photo")
    }

    // MARK: Actions

    private func startAll() {
        sensors.start()
        Task { await camera.start() }
    }

    private func stopAll() {
        sensors.stop()
        camera.stop()
    }

    private func shoot() {
        guard let snapshot = sensors.snapshot() else {
            message = "Waiting for a GPS fix."
            return
        }
        message = nil
        let optics = camera.currentOptics()
        let input = CaptureBuilder.input(snapshot: snapshot, optics: optics)
        let sensorsJSON = CaptureBuilder.sensorsJSON(snapshot: snapshot, optics: optics)
        let chosenLicense = license
        let chosenTitle = title
        saving = true
        Task {
            defer { saving = false }
            do {
                let data = try await camera.capturePhoto(rotationAngle: snapshot.imageUp.captureRotationAngle)
                withAnimation(.easeOut(duration: 0.08)) { flash = true }
                try? await Task.sleep(nanoseconds: 120_000_000)
                withAnimation(.easeIn(duration: 0.2)) { flash = false }
                try await queue.addCapture(photoData: data, input: input, title: chosenTitle,
                                           license: chosenLicense, sensorsJSON: sensorsJSON)
                title = ""
            } catch {
                message = error.localizedDescription
            }
        }
    }
}
