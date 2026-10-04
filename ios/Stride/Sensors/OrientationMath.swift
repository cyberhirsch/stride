import Foundation

// MARK: - Frames and conventions
//
// World frame (CoreMotion `.xTrueNorthZVertical` / `.xMagneticNorthZVertical`):
//   X = north (true or magnetic), Z = up (against gravity), Y = Z x X = WEST.
//   So a world vector (x, y, z) means (north, west, up); east = -y.
//
// Device frame (Apple): with the phone in portrait, screen facing you,
//   +X = to the right, +Y = towards the top of the phone, +Z = out of the
//   screen towards you. The back camera therefore looks along device -Z.
//
// CMAttitude.rotationMatrix R maps world coordinates to device coordinates:
//   v_device = R * v_world.
// Consequences used below:
//   * row i of R (m_i1, m_i2, m_i3) = device axis i expressed in world coords.
//   * column 3 (m13, m23, m33) = world "up" expressed in device coords, so the
//     gravity vector reported by CMDeviceMotion (device coords, pointing down)
//     equals -(m13, m23, m33). SensorManager verifies this at runtime against
//     CMDeviceMotion.gravity and transposes if the check ever disagrees.
//
// Camera pose:
//   c = optical axis in world = -(row 3) = -(m31, m32, m33)
//   heading = atan2(east, north) = atan2(-c.y, c.x), 0..360, clockwise from north
//   pitch   = asin(c.z)             (+ = looking up)
//   roll    = rotation of the stored image's "up" vector u about c, measured
//             against the level reference (rightH = normalize(c x Up),
//             upLevel = rightH x c):  roll = atan2(u . rightH, u . upLevel).
//             + = camera rotated clockwise as seen by the photographer
//             (its top tilts to the right).
//   The image up vector u depends on how the photo is stored (see ImageUpAxis).

struct Vec3: Equatable {
    var x: Double
    var y: Double
    var z: Double

    init(_ x: Double, _ y: Double, _ z: Double) {
        self.x = x
        self.y = y
        self.z = z
    }

    func dot(_ o: Vec3) -> Double { x * o.x + y * o.y + z * o.z }

    func cross(_ o: Vec3) -> Vec3 {
        Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    }

    var length: Double { (x * x + y * y + z * z).squareRoot() }

    var normalized: Vec3 {
        let l = length
        return l > 0 ? Vec3(x / l, y / l, z / l) : self
    }

    static func + (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }
    static func - (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x - b.x, a.y - b.y, a.z - b.z) }
    static prefix func - (a: Vec3) -> Vec3 { Vec3(-a.x, -a.y, -a.z) }
    static func * (a: Vec3, s: Double) -> Vec3 { Vec3(a.x * s, a.y * s, a.z * s) }
}

/// Row-major 3x3 matrix with the same element names as CMRotationMatrix.
struct Mat3: Equatable {
    var m11: Double, m12: Double, m13: Double
    var m21: Double, m22: Double, m23: Double
    var m31: Double, m32: Double, m33: Double

    init(m11: Double, m12: Double, m13: Double,
         m21: Double, m22: Double, m23: Double,
         m31: Double, m32: Double, m33: Double) {
        self.m11 = m11; self.m12 = m12; self.m13 = m13
        self.m21 = m21; self.m22 = m22; self.m23 = m23
        self.m31 = m31; self.m32 = m32; self.m33 = m33
    }

    init(rows r1: Vec3, _ r2: Vec3, _ r3: Vec3) {
        self.init(m11: r1.x, m12: r1.y, m13: r1.z,
                  m21: r2.x, m22: r2.y, m23: r2.z,
                  m31: r3.x, m32: r3.y, m33: r3.z)
    }

    var row1: Vec3 { Vec3(m11, m12, m13) }
    var row2: Vec3 { Vec3(m21, m22, m23) }
    var row3: Vec3 { Vec3(m31, m32, m33) }
    var column3: Vec3 { Vec3(m13, m23, m33) }

    var transposed: Mat3 {
        Mat3(m11: m11, m12: m21, m13: m31,
             m21: m12, m22: m22, m23: m32,
             m31: m13, m32: m23, m33: m33)
    }

    var elements: [Double] { [m11, m12, m13, m21, m22, m23, m31, m32, m33] }
}

/// Which device axis points to the top of the stored (upright) photo.
/// The capture connection's rotation angle is set from this, so the stored
/// pixels and the roll computation always agree.
enum ImageUpAxis: String, Codable, CaseIterable {
    case plusY = "+y"   // portrait
    case plusX = "+x"   // UIDeviceOrientation.landscapeLeft (home button / bottom on the right)
    case minusY = "-y"  // portrait upside down
    case minusX = "-x"  // UIDeviceOrientation.landscapeRight (bottom on the left)

    /// AVCaptureConnection.videoRotationAngle for the back camera that makes
    /// this device axis the top of the image. The back camera sensor's native
    /// orientation (angle 0) has device +X at the top.
    var captureRotationAngle: Double {
        switch self {
        case .plusX: return 0
        case .plusY: return 90
        case .minusX: return 180
        case .minusY: return 270
        }
    }

    /// This axis expressed in world coordinates (north, west, up).
    func worldVector(_ r: Mat3) -> Vec3 {
        switch self {
        case .plusX: return r.row1
        case .plusY: return r.row2
        case .minusX: return -r.row1
        case .minusY: return -r.row2
        }
    }
}

struct CameraPose: Equatable {
    /// degrees 0..<360, clockwise from north (true or magnetic, depending on the frame)
    var heading: Double
    /// degrees -90...90, + = looking up
    var pitch: Double
    /// degrees -180...180, + = clockwise as seen by the photographer
    var roll: Double
}

enum OrientationMath {
    static let radToDeg = 180.0 / Double.pi
    static let degToRad = Double.pi / 180.0

    /// Wraps any angle into 0..<360.
    static func normalizeDegrees(_ value: Double) -> Double {
        guard value.isFinite else { return 0 }
        var r = fmod(value, 360)
        if r < 0 { r += 360 }
        if r >= 360 { r = 0 }
        return r
    }

    /// Wraps any angle into -180..<180.
    static func signedDegrees(_ value: Double) -> Double {
        let n = normalizeDegrees(value)
        return n >= 180 ? n - 360 : n
    }

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }

    /// Camera pose from a world->device rotation matrix (see file header).
    static func pose(rotation r: Mat3, imageUp: ImageUpAxis) -> CameraPose {
        let c = (-r.row3).normalized          // optical axis, world (N, W, U)
        let north = c.x
        let east = -c.y
        let up = clamp(c.z, -1, 1)
        let pitch = asin(up) * radToDeg
        let u = imageUp.worldVector(r)
        let horizontal = (north * north + east * east).squareRoot()

        if horizontal > 1e-6 {
            let heading = normalizeDegrees(atan2(east, north) * radToDeg)
            let worldUp = Vec3(0, 0, 1)
            let rightH = c.cross(worldUp).normalized
            let upLevel = rightH.cross(c).normalized
            let roll = atan2(u.dot(rightH), u.dot(upLevel)) * radToDeg
            return CameraPose(heading: heading, pitch: pitch, roll: roll)
        } else {
            // Looking straight up or down: heading of the optical axis is
            // undefined. Use the direction the top of the image points to.
            let heading = normalizeDegrees(atan2(-u.y, u.x) * radToDeg)
            return CameraPose(heading: heading, pitch: pitch, roll: 0)
        }
    }

    /// Picks the device axis that is most "up" in the world, with hysteresis so
    /// the choice does not flicker near 45 degrees or when the phone lies flat.
    static func imageUpAxis(rotation r: Mat3, previous: ImageUpAxis) -> ImageUpAxis {
        func score(_ a: ImageUpAxis) -> Double { a.worldVector(r).z }
        var best = previous
        var bestScore = -Double.infinity
        for axis in ImageUpAxis.allCases {
            let s = score(axis)
            if s > bestScore {
                best = axis
                bestScore = s
            }
        }
        // Phone nearly flat (all in-plane axes close to horizontal): keep previous.
        if bestScore < 0.35 { return previous }
        // Hysteresis: only switch if clearly better than the current axis.
        if best != previous && score(previous) > bestScore - 0.2 { return previous }
        return best
    }

    /// How well the matrix matches the measured gravity vector (device coords,
    /// unit g) under both possible conventions. Smaller is better.
    /// `row` assumes v_device = R * v_world (Apple's convention, expected);
    /// `column` assumes the transpose.
    static func conventionErrors(rotation r: Mat3, gravity g: Vec3) -> (row: Double, column: Double) {
        let gn = g.normalized
        let rowErr = (r.column3 + gn).length
        let colErr = (r.row3 + gn).length
        return (rowErr, colErr)
    }

    /// Field of view of the stored image from the horizontal FOV of the sensor
    /// format (which applies to the sensor's long side) and the stored pixel
    /// size. Returns (fovH, fovV) in degrees for the stored orientation.
    static func fieldOfView(longSideFov: Double, width: Int, height: Int) -> (h: Double, v: Double)? {
        guard longSideFov > 0, longSideFov < 180, width > 0, height > 0 else { return nil }
        let long = Double(max(width, height))
        let short = Double(min(width, height))
        let halfLong = longSideFov / 2 * degToRad
        let fovShort = 2 * atan(tan(halfLong) * short / long) * radToDeg
        return width >= height ? (longSideFov, fovShort) : (fovShort, longSideFov)
    }

    /// Horizontal FOV after digital zoom.
    static func zoomedFov(_ fov: Double, zoom: Double) -> Double {
        guard zoom > 1 else { return fov }
        return 2 * atan(tan(fov / 2 * degToRad) / zoom) * radToDeg
    }
}
