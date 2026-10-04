import SwiftUI

struct PhotoDetailView: View {
    let summary: PhotoSummary

    @EnvironmentObject private var auth: AuthStore
    @Environment(\.dismiss) private var dismiss
    @State private var detail: PhotoDetail?
    @State private var errorText: String?
    @State private var showFull = false
    @State private var showReport = false

    private var client: APIClient { auth.client }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    AsyncImage(url: client.fileURL(recordId: summary.id, fileName: summary.image, thumb: "400x0")) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFit()
                        case .failure:
                            placeholder(systemImage: "photo")
                        case .empty:
                            placeholder(systemImage: nil)
                        @unknown default:
                            placeholder(systemImage: nil)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .onTapGesture { showFull = true }

                    if let errorText {
                        Text(errorText).font(.footnote).foregroundStyle(.red)
                    }
                    info
                }
                .padding()
            }
            .navigationTitle(displayTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showReport = true
                    } label: {
                        Label("Report", systemImage: "flag")
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await loadDetail() }
        .fullScreenCover(isPresented: $showFull) {
            FullImageView(url: client.fileURL(recordId: summary.id, fileName: summary.image, thumb: "1600x0"))
        }
        .sheet(isPresented: $showReport) {
            ReportView(photoId: summary.id)
                .environmentObject(auth)
        }
    }

    private var displayTitle: String {
        let t = detail?.title ?? summary.title ?? ""
        return t.isEmpty ? "Photo" : t
    }

    private func placeholder(systemImage: String?) -> some View {
        ZStack {
            Rectangle().fill(Color.gray.opacity(0.2))
            if let systemImage {
                Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .frame(height: 220)
    }

    @ViewBuilder
    private var info: some View {
        let d = detail
        VStack(alignment: .leading, spacing: 6) {
            row("Author", d?.authorName ?? "—")
            row("Taken", dateText(d?.capturedAt ?? summary.capturedAt))
            row("License", d?.license.map { License.displayName(for: $0) } ?? "—")
            if let headingText = headingText(d) {
                row("Heading", headingText)
            }
            if let d, d.hasTilt == true {
                row("Pitch / roll", String(format: "%+.1f° / %+.1f°", d.pitch ?? 0, d.roll ?? 0))
            }
            if let fov = d?.fovH ?? summary.fovH, fov > 0 {
                row("Field of view", String(format: "%.0f°", fov))
            }
            if let acc = d?.gpsAccuracy, acc > 0 {
                row("GPS accuracy", String(format: "±%.0f m", acc))
            }
            if let device = d?.device, !device.isEmpty {
                row("Device", device)
            }
            row("Position", String(format: "%.6f, %.6f", summary.lat, summary.lon))
        }
    }

    private func headingText(_ d: PhotoDetail?) -> String? {
        let measured = d.map { $0.hasHeading == true } ?? (summary.hasHeading == true)
        guard measured else { return nil }
        let heading = d?.heading ?? summary.heading ?? 0
        var text = String(format: "%.0f° true", heading)
        if let acc = d?.headingAccuracy, acc > 0 {
            text += String(format: " (±%.0f°)", acc)
        }
        return text
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary).frame(width: 110, alignment: .leading)
            Text(value)
            Spacer(minLength: 0)
        }
        .font(.subheadline)
    }

    private func dateText(_ raw: String?) -> String {
        guard let date = PBDate.parse(raw) else { return raw ?? "—" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private func loadDetail() async {
        do {
            detail = try await client.photo(id: summary.id)
        } catch {
            errorText = error.localizedDescription
        }
    }
}

struct FullImageView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFit()
                        .scaleEffect(scale)
                        .gesture(
                            MagnificationGesture()
                                .onChanged { value in scale = max(1, lastScale * value) }
                                .onEnded { _ in lastScale = scale }
                        )
                        .onTapGesture(count: 2) {
                            scale = 1
                            lastScale = 1
                        }
                case .failure:
                    Image(systemName: "photo").font(.largeTitle).foregroundStyle(.white)
                default:
                    ProgressView().tint(.white)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.85))
                    .padding()
            }
        }
    }
}

struct ReportView: View {
    let photoId: String
    @EnvironmentObject private var auth: AuthStore
    @Environment(\.dismiss) private var dismiss
    @State private var reason: ReportReason = .other
    @State private var note = ""
    @State private var sending = false
    @State private var errorText: String?
    @State private var sent = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Reason") {
                    Picker("Reason", selection: $reason) {
                        ForEach(ReportReason.allCases) { r in
                            Text(r.label).tag(r)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section("Note (optional)") {
                    TextField("What is wrong with this photo?", text: $note, axis: .vertical)
                        .lineLimit(3...6)
                }
                if let errorText {
                    Section { Text(errorText).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle("Report photo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        ProgressView()
                    } else {
                        Button("Send") { send() }
                    }
                }
            }
            .alert("Thank you", isPresented: $sent) {
                Button("OK") { dismiss() }
            } message: {
                Text("The report was sent to the moderators.")
            }
        }
    }

    private func send() {
        sending = true
        errorText = nil
        let client = auth.client
        let reporter = auth.user?.id
        let chosenReason = reason
        let text = String(note.prefix(2000))
        Task {
            defer { sending = false }
            do {
                try await client.report(photoId: photoId, reason: chosenReason, note: text, reporterId: reporter)
                sent = true
            } catch {
                errorText = error.localizedDescription
            }
        }
    }
}
