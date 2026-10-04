import XCTest
@testable import Stride

/// World frame: (x, y, z) = (north, west, up). Rows of the matrix are the
/// device axes expressed in world coordinates (see OrientationMath.swift).
final class OrientationMathTests: XCTestCase {
    private let eps = 1e-6

    private let north = Vec3(1, 0, 0)
    private let south = Vec3(-1, 0, 0)
    private let west = Vec3(0, 1, 0)
    private let east = Vec3(0, -1, 0)
    private let up = Vec3(0, 0, 1)
    private let down = Vec3(0, 0, -1)

    private func assertAngle(_ a: Double, _ b: Double, accuracy: Double = 1e-6,
                             file: StaticString = #filePath, line: UInt = #line) {
        let diff = abs(OrientationMath.signedDegrees(a - b))
        XCTAssertLessThan(diff, accuracy, "\(a) != \(b)", file: file, line: line)
    }

    private func assertRotation(_ r: Mat3, file: StaticString = #filePath, line: UInt = #line) {
        // Proper rotation: rows orthonormal and row1 x row2 = row3.
        XCTAssertEqual(r.row1.length, 1, accuracy: eps, file: file, line: line)
        XCTAssertEqual(r.row2.length, 1, accuracy: eps, file: file, line: line)
        XCTAssertEqual(r.row3.length, 1, accuracy: eps, file: file, line: line)
        let z = r.row1.cross(r.row2)
        XCTAssertEqual((z - r.row3).length, 0, accuracy: eps, file: file, line: line)
    }

    /// Builds the world->device matrix for a camera pose.
    private func matrix(heading: Double, pitch: Double, roll: Double, imageUp: ImageUpAxis) -> Mat3 {
        let h = heading * Double.pi / 180
        let p = pitch * Double.pi / 180
        let r = roll * Double.pi / 180
        // optical axis in (north, west, up)
        let c = Vec3(cos(p) * cos(h), -cos(p) * sin(h), sin(p))
        let rightH = Vec3(-sin(h), -cos(h), 0)          // horizontal, 90 deg clockwise of heading
        let upLevel = rightH.cross(c)
        let u = upLevel * cos(r) + rightH * sin(r)       // image up
        let rgt = rightH * cos(r) - upLevel * sin(r)     // image right
        let z = -c
        switch imageUp {
        case .plusY: return Mat3(rows: rgt, u, z)
        case .plusX: return Mat3(rows: u, -rgt, z)
        case .minusY: return Mat3(rows: -rgt, -u, z)
        case .minusX: return Mat3(rows: -u, rgt, z)
        }
    }

    // MARK: Hand-written matrices (convention checks)

    func testPortraitLookingNorthLevel() {
        // Right side east, top up, screen facing south (camera looks north).
        let r = Mat3(rows: east, up, south)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 0)
        XCTAssertEqual(p.pitch, 0, accuracy: eps)
        XCTAssertEqual(p.roll, 0, accuracy: eps)
    }

    func testPortraitLookingEast() {
        // Camera looks east, so the screen faces west and the right side points south.
        let r = Mat3(rows: south, up, west)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 90)
        XCTAssertEqual(p.pitch, 0, accuracy: eps)
        XCTAssertEqual(p.roll, 0, accuracy: eps)
    }

    func testPortraitLookingWest() {
        let r = Mat3(rows: north, up, east)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 270)
    }

    func testPortraitLookingSouth() {
        let r = Mat3(rows: west, up, north)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 180)
    }

    func testPortraitPitchedUp30() {
        let s = sin(30 * Double.pi / 180)
        let c = cos(30 * Double.pi / 180)
        // top tilted back, camera looks north and up
        let r = Mat3(rows: east, Vec3(-s, 0, c), Vec3(-c, 0, -s))
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 0)
        XCTAssertEqual(p.pitch, 30, accuracy: eps)
        XCTAssertEqual(p.roll, 0, accuracy: eps)
    }

    func testPortraitRolledClockwise10() {
        let s = sin(10 * Double.pi / 180)
        let c = cos(10 * Double.pi / 180)
        // Camera rotated clockwise (as seen by the photographer): its top tilts east.
        let r = Mat3(rows: Vec3(0, -c, -s), Vec3(0, -s, c), south)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        assertAngle(p.heading, 0)
        XCTAssertEqual(p.pitch, 0, accuracy: eps)
        XCTAssertEqual(p.roll, 10, accuracy: eps)
    }

    func testLandscapeLeftLookingNorth() {
        // UIDeviceOrientation.landscapeLeft: device +X up, +Y to the left (west).
        let r = Mat3(rows: up, west, south)
        assertRotation(r)
        XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r, previous: .plusY), .plusX)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusX)
        assertAngle(p.heading, 0)
        XCTAssertEqual(p.pitch, 0, accuracy: eps)
        XCTAssertEqual(p.roll, 0, accuracy: eps)
        // Interpreted as a portrait image, the same pose is rolled 90 deg counter-clockwise.
        let portrait = OrientationMath.pose(rotation: r, imageUp: .plusY)
        XCTAssertEqual(portrait.roll, -90, accuracy: eps)
    }

    func testLandscapeRightLookingEast() {
        // UIDeviceOrientation.landscapeRight: device -X up; camera looks east,
        // screen faces west, device +Y points right (south).
        let r = Mat3(rows: down, south, west)
        assertRotation(r)
        XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r, previous: .plusY), .minusX)
        let p = OrientationMath.pose(rotation: r, imageUp: .minusX)
        assertAngle(p.heading, 90)
        XCTAssertEqual(p.pitch, 0, accuracy: eps)
        XCTAssertEqual(p.roll, 0, accuracy: eps)
    }

    func testFlatFaceUpLooksDown() {
        // Lying flat, screen up, top pointing east: camera looks straight down.
        let r = Mat3(rows: south, east, up)
        assertRotation(r)
        let p = OrientationMath.pose(rotation: r, imageUp: .plusY)
        XCTAssertEqual(p.pitch, -90, accuracy: eps)
        assertAngle(p.heading, 90) // falls back to the direction of the image top
        XCTAssertEqual(p.roll, 0, accuracy: eps)
        // Flat: keep the previous image-up axis.
        XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r, previous: .minusX), .minusX)
    }

    // MARK: Round trips

    func testRoundTripAllOrientations() {
        for axis in ImageUpAxis.allCases {
            for heading in stride(from: 0.0, to: 360.0, by: 37.0) {
                for pitch in [-75.0, -30.0, 0.0, 12.5, 60.0] {
                    for roll in [-150.0, -20.0, 0.0, 5.0, 45.0, 170.0] {
                        let r = matrix(heading: heading, pitch: pitch, roll: roll, imageUp: axis)
                        assertRotation(r)
                        let p = OrientationMath.pose(rotation: r, imageUp: axis)
                        assertAngle(p.heading, heading, accuracy: 1e-6)
                        XCTAssertEqual(p.pitch, pitch, accuracy: 1e-6)
                        assertAngle(p.roll, roll, accuracy: 1e-6)
                    }
                }
            }
        }
    }

    func testImageUpAxisSelection() {
        for axis in ImageUpAxis.allCases {
            let r = matrix(heading: 200, pitch: 10, roll: 0, imageUp: axis)
            XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r, previous: .plusY), axis)
        }
        // Hysteresis: at 45 degrees between portrait and landscape keep the previous axis.
        let r45 = matrix(heading: 0, pitch: 0, roll: 45, imageUp: .plusY)
        XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r45, previous: .plusY), .plusY)
        let r45b = matrix(heading: 0, pitch: 0, roll: -45, imageUp: .plusX)
        XCTAssertEqual(OrientationMath.imageUpAxis(rotation: r45b, previous: .plusX), .plusX)
    }

    func testCaptureRotationAngles() {
        XCTAssertEqual(ImageUpAxis.plusY.captureRotationAngle, 90)
        XCTAssertEqual(ImageUpAxis.plusX.captureRotationAngle, 0)
        XCTAssertEqual(ImageUpAxis.minusY.captureRotationAngle, 270)
        XCTAssertEqual(ImageUpAxis.minusX.captureRotationAngle, 180)
    }

    // MARK: Gravity convention

    func testGravityConventionCheck() {
        let r = matrix(heading: 123, pitch: 20, roll: -7, imageUp: .plusY)
        // Gravity in device coordinates = R * (0, 0, -1) = -(column 3).
        let g = -r.column3
        let errors = OrientationMath.conventionErrors(rotation: r, gravity: g)
        XCTAssertEqual(errors.row, 0, accuracy: eps)
        XCTAssertGreaterThan(errors.column, 0.3)
        let swapped = OrientationMath.conventionErrors(rotation: r.transposed, gravity: g)
        XCTAssertEqual(swapped.column, 0, accuracy: eps)
    }

    // MARK: Helpers

    func testNormalizeDegrees() {
        XCTAssertEqual(OrientationMath.normalizeDegrees(-10), 350, accuracy: eps)
        XCTAssertEqual(OrientationMath.normalizeDegrees(370), 10, accuracy: eps)
        XCTAssertEqual(OrientationMath.normalizeDegrees(360), 0, accuracy: eps)
        XCTAssertEqual(OrientationMath.signedDegrees(190), -170, accuracy: eps)
    }

    func testFieldOfView() {
        let landscape = OrientationMath.fieldOfView(longSideFov: 70, width: 4032, height: 3024)
        let expectedShort = 2 * atan(tan(35 * Double.pi / 180) * 0.75) * 180 / Double.pi
        XCTAssertEqual(landscape?.h ?? 0, 70, accuracy: eps)
        XCTAssertEqual(landscape?.v ?? 0, expectedShort, accuracy: eps)

        let portrait = OrientationMath.fieldOfView(longSideFov: 70, width: 3024, height: 4032)
        XCTAssertEqual(portrait?.h ?? 0, expectedShort, accuracy: eps)
        XCTAssertEqual(portrait?.v ?? 0, 70, accuracy: eps)

        XCTAssertNil(OrientationMath.fieldOfView(longSideFov: 0, width: 10, height: 10))
        XCTAssertEqual(OrientationMath.zoomedFov(70, zoom: 1), 70, accuracy: eps)
        XCTAssertLessThan(OrientationMath.zoomedFov(70, zoom: 2), 40)
    }
}

final class UploadFieldsTests: XCTestCase {
    func testFormFieldsFlagsAndRanges() {
        let fields = UploadFields(
            title: "  Bridge  ", license: "cc-by", device: "Apple iPhone16,1",
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000.25),
            lat: 47.5, lon: -122.25, gpsAccuracy: 4, altitude: 100, altitudeAccuracy: -1,
            heading: 359.9999, headingAccuracy: 8, pitch: 3, roll: -2,
            focalLengthMm: 5.1, focalLength35mm: 26, fovH: 65, fovV: 51,
            width: 3024, height: 4032, sensorsJSON: "{}")
        let dict = Dictionary(fields.formFields(authorId: "u1"), uniquingKeysWith: { a, _ in a })
        XCTAssertEqual(dict["author"], "u1")
        XCTAssertEqual(dict["platform"], "ios")
        XCTAssertEqual(dict["title"], "Bridge")
        XCTAssertEqual(dict["has_heading"], "true")
        XCTAssertEqual(dict["has_tilt"], "true")
        XCTAssertEqual(dict["captured_at"], "2023-11-14T22:13:20.250Z")
        XCTAssertNil(dict["altitude_accuracy"])
        XCTAssertEqual(dict["lon"], "-122.25000000")

        var noSensors = fields
        noSensors.heading = nil
        noSensors.pitch = nil
        let d2 = Dictionary(noSensors.formFields(authorId: "u1"), uniquingKeysWith: { a, _ in a })
        XCTAssertEqual(d2["has_heading"], "false")
        XCTAssertEqual(d2["has_tilt"], "false")
        XCTAssertNil(d2["heading"])
    }

    func testBoundingBoxFilter() {
        let normal = APIClient.filter(for: BoundingBox(south: 1, west: 2, north: 3, east: 4))
        XCTAssertEqual(normal, "(lat>=1.000000 && lat<=3.000000 && lon>=2.000000 && lon<=4.000000)")
        let wrapped = APIClient.filter(for: BoundingBox(south: 1, west: 170, north: 3, east: -170))
        XCTAssertTrue(wrapped.contains("||"))
        XCTAssertEqual(APIClient.encode("a&b=c d"), "a%26b%3Dc%20d")
    }
}
