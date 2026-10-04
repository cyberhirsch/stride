import SwiftUI
import MapKit

/// One thing to draw on the map: a single photo or a grid cluster.
struct MapItem: Identifiable {
    enum Kind {
        case photo(PhotoSummary)
        case cluster(count: Int)
    }

    let id: String
    let coordinate: CLLocationCoordinate2D
    let kind: Kind
}

enum MapClustering {
    /// Below this latitude span (degrees) every photo is drawn individually.
    static let clusterSpanThreshold = 0.01
    static let gridDivisions = 8.0

    static func items(for photos: [PhotoSummary], region: MKCoordinateRegion?) -> [MapItem] {
        guard let region, region.span.latitudeDelta > clusterSpanThreshold, photos.count > 1 else {
            return photos.map { MapItem(id: $0.id, coordinate: $0.coordinate, kind: .photo($0)) }
        }
        let cellLat = max(region.span.latitudeDelta / gridDivisions, 1e-6)
        let cellLon = max(region.span.longitudeDelta / gridDivisions, 1e-6)

        struct Cell: Hashable { let x: Int; let y: Int }
        var buckets: [Cell: [PhotoSummary]] = [:]
        for photo in photos {
            let cell = Cell(x: Int(floor(photo.lon / cellLon)), y: Int(floor(photo.lat / cellLat)))
            buckets[cell, default: []].append(photo)
        }

        var result: [MapItem] = []
        for (cell, members) in buckets {
            if members.count == 1, let only = members.first {
                result.append(MapItem(id: only.id, coordinate: only.coordinate, kind: .photo(only)))
            } else {
                let lat = members.reduce(0.0) { $0 + $1.lat } / Double(members.count)
                let lon = members.reduce(0.0) { $0 + $1.lon } / Double(members.count)
                result.append(MapItem(id: "cluster-\(cell.x)-\(cell.y)-\(members.count)",
                                      coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                                      kind: .cluster(count: members.count)))
            }
        }
        return result
    }

    static func boundingBox(for region: MKCoordinateRegion) -> BoundingBox {
        let c = region.center
        let halfLat = region.span.latitudeDelta / 2
        let halfLon = region.span.longitudeDelta / 2
        let south = max(-90, c.latitude - halfLat)
        let north = min(90, c.latitude + halfLat)
        if region.span.longitudeDelta >= 360 {
            return BoundingBox(south: south, west: -180, north: north, east: 180)
        }
        func wrap(_ lon: Double) -> Double {
            var v = lon
            while v < -180 { v += 360 }
            while v > 180 { v -= 360 }
            return v
        }
        return BoundingBox(south: south, west: wrap(c.longitude - halfLon), north: north, east: wrap(c.longitude + halfLon))
    }
}

struct MapScreen: View {
    @EnvironmentObject private var auth: AuthStore
    @State private var position: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var region: MKCoordinateRegion?
    @State private var mapHeading: Double = 0
    @State private var photos: [PhotoSummary] = []
    @State private var loadTask: Task<Void, Never>?
    @State private var loading = false
    @State private var errorText: String?
    @State private var selected: PhotoSummary?

    var body: some View {
        let items = MapClustering.items(for: photos, region: region)
        Map(position: $position) {
            UserAnnotation()
            ForEach(items) { item in
                Annotation("", coordinate: item.coordinate, anchor: .center) {
                    annotationView(item)
                }
            }
        }
        .annotationTitles(.hidden)
        .mapControls {
            MapUserLocationButton()
            MapCompass()
            MapScaleView()
        }
        .onMapCameraChange(frequency: .onEnd) { context in
            region = context.region
            mapHeading = context.camera.heading
            scheduleLoad(context.region)
        }
        .overlay(alignment: .top) {
            if loading || errorText != nil {
                HStack(spacing: 6) {
                    if loading { ProgressView() }
                    if let errorText { Text(errorText).font(.caption).lineLimit(2) }
                }
                .padding(8)
                .background(.regularMaterial, in: Capsule())
                .padding(.top, 8)
            }
        }
        .sheet(item: $selected) { photo in
            PhotoDetailView(summary: photo)
                .environmentObject(auth)
        }
    }

    @ViewBuilder
    private func annotationView(_ item: MapItem) -> some View {
        switch item.kind {
        case .photo(let photo):
            Button {
                selected = photo
            } label: {
                PhotoPin(heading: photo.measuredHeading.map { $0 - mapHeading }, fov: photo.fovH)
            }
            .buttonStyle(.plain)
        case .cluster(let count):
            Button {
                zoom(into: item.coordinate)
            } label: {
                Text("\(count)")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .frame(minWidth: 30, minHeight: 30)
                    .padding(.horizontal, 4)
                    .background(Circle().fill(Color.blue))
                    .overlay(Circle().stroke(Color.white, lineWidth: 2))
            }
            .buttonStyle(.plain)
        }
    }

    private func zoom(into coordinate: CLLocationCoordinate2D) {
        guard let region else { return }
        let span = MKCoordinateSpan(latitudeDelta: region.span.latitudeDelta / 4,
                                    longitudeDelta: region.span.longitudeDelta / 4)
        withAnimation {
            position = .region(MKCoordinateRegion(center: coordinate, span: span))
        }
    }

    private func scheduleLoad(_ region: MKCoordinateRegion) {
        loadTask?.cancel()
        loadTask = Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            await load(region)
        }
    }

    private func load(_ region: MKCoordinateRegion) async {
        loading = true
        defer { loading = false }
        do {
            let box = MapClustering.boundingBox(for: region)
            let result = try await auth.client.photos(in: box)
            if Task.isCancelled { return }
            photos = result
            errorText = nil
        } catch {
            if Task.isCancelled { return }
            if (error as? URLError)?.code == .cancelled { return }
            errorText = error.localizedDescription
        }
    }
}

/// Dot with a view cone pointing along the (map-relative) heading.
struct PhotoPin: View {
    let heading: Double?
    let fov: Double?

    var body: some View {
        ZStack {
            if let heading {
                ViewCone(fov: coneFov)
                    .fill(Color.blue.opacity(0.35))
                    .overlay(ViewCone(fov: coneFov).stroke(Color.blue.opacity(0.8), lineWidth: 1))
                    .frame(width: 48, height: 48)
                    .rotationEffect(.degrees(heading))
            }
            Circle()
                .fill(Color.blue)
                .frame(width: 12, height: 12)
                .overlay(Circle().stroke(Color.white, lineWidth: 2))
        }
        .frame(width: 48, height: 48)
        .contentShape(Rectangle())
    }

    private var coneFov: Double {
        guard let fov, fov > 5, fov < 170 else { return 60 }
        return fov
    }
}

/// A wedge from the centre pointing up (north before rotation), `fov` wide.
/// Built from explicit points to avoid arc-direction ambiguity.
struct ViewCone: Shape {
    var fov: Double

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        let steps = 12
        var path = Path()
        path.move(to: center)
        for i in 0...steps {
            // Angle from "up", clockwise, in degrees.
            let a = (-fov / 2 + fov * Double(i) / Double(steps)) * Double.pi / 180
            let x = center.x + radius * CGFloat(sin(a))
            let y = center.y - radius * CGFloat(cos(a))
            path.addLine(to: CGPoint(x: x, y: y))
        }
        path.closeSubpath()
        return path
    }
}
