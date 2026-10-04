import SwiftUI
import UIKit

struct QueueView: View {
    @EnvironmentObject private var queue: UploadQueue

    var body: some View {
        NavigationStack {
            Group {
                if queue.records.isEmpty {
                    ContentUnavailableView("No photos yet", systemImage: "photo.on.rectangle",
                                           description: Text("Photos you take are stored here and uploaded automatically."))
                } else {
                    List {
                        ForEach(queue.records) { record in
                            QueueRow(record: record, thumbnail: queue.thumbnailURL(record.id))
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        queue.remove(record.id)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    if record.status == .failed || record.status == .pending {
                                        Button {
                                            queue.retry(record.id)
                                        } label: {
                                            Label("Retry", systemImage: "arrow.clockwise")
                                        }
                                        .tint(.blue)
                                    }
                                }
                        }
                    }
                    .refreshable { queue.kick() }
                }
            }
            .navigationTitle("Uploads")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Clear uploaded") { queue.clearUploaded() }
                        .disabled(!queue.records.contains { $0.status == .uploaded })
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Retry all") { queue.retryAll() }
                }
            }
        }
    }
}

private struct QueueRow: View {
    let record: CaptureRecord
    let thumbnail: URL

    var body: some View {
        HStack(spacing: 12) {
            thumbnailView
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text(record.fields.title.isEmpty ? "Untitled" : record.fields.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(record.fields.capturedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(detailLine)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: statusIcon)
                    Text(record.status.label)
                    if record.attempts > 0 && record.status != .uploaded {
                        Text("(\(record.attempts) tries)")
                    }
                }
                .font(.caption)
                .foregroundStyle(statusColor)
                if let error = record.lastError, record.status != .uploaded {
                    Text(error)
                        .font(.caption2)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                }
            }
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if let image = UIImage(contentsOfFile: thumbnail.path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            Rectangle().fill(Color.gray.opacity(0.3))
        }
    }

    private var detailLine: String {
        let f = record.fields
        var parts = [String(format: "%.5f, %.5f", f.lat, f.lon)]
        if let h = f.heading { parts.append(String(format: "%.0f°", h)) }
        if let p = f.pitch { parts.append(String(format: "pitch %+.0f°", p)) }
        return parts.joined(separator: " · ")
    }

    private var statusIcon: String {
        switch record.status {
        case .pending: return "clock"
        case .uploading: return "arrow.up.circle"
        case .uploaded: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.circle.fill"
        }
    }

    private var statusColor: Color {
        switch record.status {
        case .pending: return .orange
        case .uploading: return .blue
        case .uploaded: return .green
        case .failed: return .red
        }
    }
}
