import Foundation
import CoreLocation

/// Turns a sensor snapshot + optics into the plain capture input and the
/// free-form `sensors` JSON for the upload.
enum CaptureBuilder {
    static func input(snapshot s: SensorSnapshot, optics: OpticsInfo?) -> CaptureInput {
        let loc = s.location
        let verticalOK = loc.verticalAccuracy > 0
        // Heading is only reported when it is relative to true north.
        let heading: Double? = (s.pose != nil && s.headingSource.isTrue) ? s.pose?.heading : nil
        return CaptureInput(
            capturedAt: s.takenAt,
            timeZone: TimeZone.current,
            latitude: loc.coordinate.latitude,
            longitude: loc.coordinate.longitude,
            horizontalAccuracy: loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : nil,
            mslAltitude: verticalOK ? loc.altitude : nil,
            ellipsoidalAltitude: verticalOK ? loc.ellipsoidalAltitude : nil,
            verticalAccuracy: verticalOK ? loc.verticalAccuracy : nil,
            heading: heading,
            headingAccuracy: heading != nil ? s.headingAccuracy : nil,
            pitch: s.pose?.pitch,
            roll: s.pose?.roll,
            longSideFov: optics?.effectiveLongSideFov
        )
    }

    static func sensorsJSON(snapshot s: SensorSnapshot, optics: OpticsInfo?) -> String {
        let loc = s.location
        var dict: [String: Any] = [
            "app_version": AppConfig.appVersion,
            "heading_source": s.headingSource.rawValue,
            "reference_frame": s.referenceFrame,
            "calibration": s.calibration,
            "image_up_axis": s.imageUp.rawValue,
            "matrix_transposed": s.matrixTransposed,
            "reduced_location_accuracy": s.reducedAccuracy,
        ]
        if let d = s.declination, d.isFinite { dict["magnetic_declination"] = round3(d) }
        if let a = s.headingAccuracy, a.isFinite { dict["heading_accuracy_clheading"] = round3(a) }
        if s.headingSource == .magneticUncorrected, let p = s.pose {
            // Not true north, so not sent as `heading`; kept for diagnostics.
            dict["magnetic_heading"] = round3(p.heading)
        }

        var location: [String: Any] = [
            "fix_age_s": round3(s.takenAt.timeIntervalSince(loc.timestamp)),
            "horizontal_accuracy": round3(loc.horizontalAccuracy),
            "vertical_accuracy": round3(loc.verticalAccuracy),
        ]
        if loc.verticalAccuracy > 0 {
            location["altitude_msl"] = round3(loc.altitude)
            location["altitude_ellipsoidal"] = round3(loc.ellipsoidalAltitude)
        }
        if loc.speed >= 0 { location["speed"] = round3(loc.speed) }
        if loc.course >= 0 { location["course"] = round3(loc.course) }
        dict["location"] = location

        if let m = s.matrix { dict["rotation_matrix"] = m.elements.map(round6) }
        if let g = s.gravity { dict["gravity"] = [round6(g.x), round6(g.y), round6(g.z)] }

        if let optics {
            dict["optics"] = [
                "video_fov_long_side": round3(optics.videoFieldOfView),
                "zoom": round3(optics.zoomFactor),
                "format_width": optics.formatWidth,
                "format_height": optics.formatHeight,
                "device_type": optics.deviceType,
            ] as [String: Any]
        }

        dict["samples"] = s.samples.map { sample -> [String: Any] in
            [
                "t": round3(sample.t),
                "heading": round3(sample.heading),
                "pitch": round3(sample.pitch),
                "roll": round3(sample.roll),
            ]
        }

        guard JSONSerialization.isValidJSONObject(dict),
              let data = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    private static func round3(_ v: Double) -> Double {
        guard v.isFinite else { return 0 }
        return (v * 1000).rounded() / 1000
    }

    private static func round6(_ v: Double) -> Double {
        guard v.isFinite else { return 0 }
        return (v * 1_000_000).rounded() / 1_000_000
    }
}
