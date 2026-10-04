import Foundation
import CoreLocation
import CoreMotion

enum HeadingSource: String {
    case trueNorth = "coremotion_true_north"
    case magneticCorrected = "coremotion_magnetic+declination"
    case magneticUncorrected = "coremotion_magnetic_uncorrected"
    case unavailable = "none"

    var isTrue: Bool { self == .trueNorth || self == .magneticCorrected }
}

struct PoseSample {
    let t: Double
    let heading: Double
    let pitch: Double
    let roll: Double
}

/// Everything the sensors knew at the moment the shutter was pressed.
struct SensorSnapshot {
    let takenAt: Date
    let location: CLLocation
    let pose: CameraPose?
    let headingSource: HeadingSource
    let headingAccuracy: Double?
    let declination: Double?
    let calibration: String
    let referenceFrame: String
    let imageUp: ImageUpAxis
    let matrix: Mat3?
    let gravity: Vec3?
    let matrixTransposed: Bool
    let samples: [PoseSample]
    let reducedAccuracy: Bool
}

/// Location (CoreLocation) and orientation (CoreMotion) for the camera HUD
/// and the capture metadata. Runs only while the camera tab is visible.
@MainActor
final class SensorManager: NSObject, ObservableObject {
    @Published private(set) var location: CLLocation?
    @Published private(set) var pose: CameraPose?
    @Published private(set) var headingAccuracy: Double = -1
    @Published private(set) var headingSource: HeadingSource = .unavailable
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined
    @Published private(set) var reducedAccuracy = false
    @Published private(set) var motionAvailable = true

    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionManager()
    private var running = false
    private var activeFrame: CMAttitudeReferenceFrame?
    private var trueNorthFailed = false
    private var declination: Double?
    private var calibration = "unknown"
    private(set) var imageUp: ImageUpAxis = .plusY
    private var latestPose: CameraPose?
    private var latestSource: HeadingSource = .unavailable
    private var latestMatrix: Mat3?
    private var latestGravity: Vec3?
    private var matrixTransposed = false
    private var samples: [PoseSample] = []
    private var lastPublish: TimeInterval = 0

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.headingFilter = kCLHeadingFilterNone
        authorization = locationManager.authorizationStatus
        reducedAccuracy = locationManager.accuracyAuthorization == .reducedAccuracy
    }

    var isLocationAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    func start() {
        guard !running else { return }
        running = true
        if authorization == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
        locationManager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        }
        startMotion()
    }

    func stop() {
        guard running else { return }
        running = false
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        motionManager.stopDeviceMotionUpdates()
        activeFrame = nil
    }

    // MARK: Motion

    private func preferredFrame() -> CMAttitudeReferenceFrame {
        let frames = CMMotionManager.availableAttitudeReferenceFrames()
        if !trueNorthFailed && isLocationAuthorized && frames.contains(.xTrueNorthZVertical) {
            return .xTrueNorthZVertical
        }
        if frames.contains(.xMagneticNorthZVertical) {
            return .xMagneticNorthZVertical
        }
        return .xArbitraryCorrectedZVertical
    }

    private func startMotion() {
        guard running else { return }
        guard motionManager.isDeviceMotionAvailable else {
            motionAvailable = false
            return
        }
        let frame = preferredFrame()
        if motionManager.isDeviceMotionActive {
            if activeFrame == frame { return }
            motionManager.stopDeviceMotionUpdates()
        }
        activeFrame = frame
        motionManager.deviceMotionUpdateInterval = 1.0 / 30.0
        motionManager.showsDeviceMovementDisplay = true
        // Delivered on the main queue, so assuming main-actor isolation is safe.
        motionManager.startDeviceMotionUpdates(using: frame, to: OperationQueue.main) { [weak self] motion, error in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let error {
                    self.handleMotionError(error, frame: frame)
                } else if let motion {
                    self.handle(motion, frame: frame)
                }
            }
        }
    }

    private func handleMotionError(_ error: Error, frame: CMAttitudeReferenceFrame) {
        if frame == .xTrueNorthZVertical {
            trueNorthFailed = true
            motionManager.stopDeviceMotionUpdates()
            activeFrame = nil
            startMotion()
        }
    }

    private func handle(_ motion: CMDeviceMotion, frame: CMAttitudeReferenceFrame) {
        let m = motion.attitude.rotationMatrix
        var r = Mat3(m11: m.m11, m12: m.m12, m13: m.m13,
                     m21: m.m21, m22: m.m22, m23: m.m23,
                     m31: m.m31, m32: m.m32, m33: m.m33)
        let g = Vec3(motion.gravity.x, motion.gravity.y, motion.gravity.z)

        // Guard against a transposed matrix convention (see OrientationMath).
        let err = OrientationMath.conventionErrors(rotation: r, gravity: g)
        if err.column < 0.15 && err.row > 0.3 {
            matrixTransposed = true
        } else if err.row < 0.15 && err.column > 0.3 {
            matrixTransposed = false
        }
        if matrixTransposed { r = r.transposed }

        imageUp = OrientationMath.imageUpAxis(rotation: r, previous: imageUp)
        var p = OrientationMath.pose(rotation: r, imageUp: imageUp)

        let source: HeadingSource
        if frame == .xTrueNorthZVertical {
            source = .trueNorth
        } else if frame == .xMagneticNorthZVertical {
            if let d = declination {
                p.heading = OrientationMath.normalizeDegrees(p.heading + d)
                source = .magneticCorrected
            } else {
                source = .magneticUncorrected
            }
        } else {
            source = .unavailable
        }

        switch motion.magneticField.accuracy {
        case .uncalibrated: calibration = "uncalibrated"
        case .low: calibration = "low"
        case .medium: calibration = "medium"
        case .high: calibration = "high"
        @unknown default: calibration = "unknown"
        }

        latestPose = p
        latestSource = source
        latestMatrix = r
        latestGravity = g
        samples.append(PoseSample(t: motion.timestamp, heading: p.heading, pitch: p.pitch, roll: p.roll))
        if samples.count > 15 { samples.removeFirst(samples.count - 15) }

        // Throttle UI updates to ~10 Hz.
        if motion.timestamp - lastPublish >= 0.1 {
            lastPublish = motion.timestamp
            pose = p
            if headingSource != source { headingSource = source }
        }
    }

    private static func frameName(_ f: CMAttitudeReferenceFrame?) -> String {
        guard let f else { return "none" }
        if f == .xTrueNorthZVertical { return "xTrueNorthZVertical" }
        if f == .xMagneticNorthZVertical { return "xMagneticNorthZVertical" }
        if f == .xArbitraryCorrectedZVertical { return "xArbitraryCorrectedZVertical" }
        return "xArbitraryZVertical"
    }

    // MARK: Snapshot

    /// Nil while there is no usable location fix.
    func snapshot() -> SensorSnapshot? {
        guard let loc = location, loc.horizontalAccuracy >= 0 else { return nil }
        let samplesNow = samples
        let t0 = samplesNow.last?.t ?? 0
        let relative = samplesNow.map { PoseSample(t: $0.t - t0, heading: $0.heading, pitch: $0.pitch, roll: $0.roll) }
        return SensorSnapshot(
            takenAt: Date(),
            location: loc,
            pose: latestPose,
            headingSource: latestPose == nil ? .unavailable : latestSource,
            headingAccuracy: headingAccuracy >= 0 ? headingAccuracy : nil,
            declination: declination,
            calibration: calibration,
            referenceFrame: Self.frameName(activeFrame),
            imageUp: imageUp,
            matrix: latestMatrix,
            gravity: latestGravity,
            matrixTransposed: matrixTransposed,
            samples: relative,
            reducedAccuracy: reducedAccuracy
        )
    }

    // MARK: Delegate hops

    fileprivate func didUpdate(location loc: CLLocation) {
        guard loc.horizontalAccuracy >= 0 else { return }
        location = loc
    }

    fileprivate func didUpdate(heading: CLHeading) {
        headingAccuracy = heading.headingAccuracy
        if heading.trueHeading >= 0 && heading.headingAccuracy >= 0 {
            declination = OrientationMath.signedDegrees(heading.trueHeading - heading.magneticHeading)
        }
    }

    fileprivate func didChangeAuthorization(_ status: CLAuthorizationStatus, reduced: Bool) {
        authorization = status
        reducedAccuracy = reduced
        if running {
            locationManager.startUpdatingLocation()
            startMotion() // switches to true north once location is authorised
        }
    }
}

extension SensorManager: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in self.didUpdate(location: last) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        Task { @MainActor in self.didUpdate(heading: newHeading) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        let reduced = manager.accuracyAuthorization == .reducedAccuracy
        Task { @MainActor in self.didChangeAuthorization(status, reduced: reduced) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient (e.g. kCLErrorLocationUnknown); keep the last fix.
    }
}
