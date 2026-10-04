import Foundation
import ImageIO
import CoreGraphics

/// Plain values describing one capture; safe to hand to a background task.
struct CaptureInput: Sendable {
    let capturedAt: Date
    let timeZone: TimeZone
    let latitude: Double
    let longitude: Double
    let horizontalAccuracy: Double?
    /// Above mean sea level (CLLocation.altitude), written to EXIF GPSAltitude.
    let mslAltitude: Double?
    /// Above the WGS84 ellipsoid, sent as the `altitude` form field.
    let ellipsoidalAltitude: Double?
    let verticalAccuracy: Double?
    /// True heading of the optical axis, nil if not measured / not true north.
    let heading: Double?
    let headingAccuracy: Double?
    let pitch: Double?
    let roll: Double?
    /// Long-side FOV of the active format after zoom, degrees.
    let longSideFov: Double?
}

struct ProcessedPhoto: Sendable {
    let jpeg: Data
    let thumbnail: Data?
    let width: Int
    let height: Int
    let focalLengthMm: Double?
    let focalLength35mm: Double?
    let fovH: Double?
    let fovV: Double?
}

enum PhotoProcessingError: LocalizedError {
    case unreadable
    case encodeFailed

    var errorDescription: String? {
        switch self {
        case .unreadable: return "The captured image could not be read."
        case .encodeFailed: return "The JPEG could not be written."
        }
    }
}

/// Bakes the orientation into the pixels (EXIF Orientation = 1) and writes
/// GPS / EXIF / XMP metadata with ImageIO.
///
/// Two passes:
///  1. decode upright, re-encode with a merged property dictionary
///     (EXIF, GPS, TIFF) via CGImageDestinationAddImage;
///  2. losslessly copy that JPEG with CGImageDestinationCopyImageSource,
///     merging an XMP packet in the `stride` namespace
///     (kCGImageDestinationMetadata + kCGImageDestinationMergeMetadata).
enum PhotoProcessor {
    static let xmpNamespace = "https://stride.app/ns/1.0/"
    static let xmpPrefix = "stride"
    private static let jpegType = "public.jpeg" as CFString

    static func process(_ data: Data, input: CaptureInput) throws -> ProcessedPhoto {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw PhotoProcessingError.unreadable
        }
        let original = (CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]) ?? [:]
        let exifIn = (original[kCGImagePropertyExifDictionary as String] as? [String: Any]) ?? [:]
        let focal = (exifIn[kCGImagePropertyExifFocalLength as String] as? NSNumber)?.doubleValue
        let focal35 = (exifIn[kCGImagePropertyExifFocalLenIn35mmFilm as String] as? NSNumber)?.doubleValue
        let srcW = (original[kCGImagePropertyPixelWidth as String] as? NSNumber)?.intValue ?? 0
        let srcH = (original[kCGImagePropertyPixelHeight as String] as? NSNumber)?.intValue ?? 0
        let maxSide = max(srcW, srcH) > 0 ? max(srcW, srcH) : 8192

        let uprightOptions: [String: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: maxSide,
            kCGImageSourceShouldCacheImmediately as String: true,
        ]
        guard let upright = CGImageSourceCreateThumbnailAtIndex(source, 0, uprightOptions as CFDictionary) else {
            throw PhotoProcessingError.unreadable
        }
        let width = upright.width
        let height = upright.height

        var fovH: Double?
        var fovV: Double?
        if let fov = input.longSideFov,
           let pair = OrientationMath.fieldOfView(longSideFov: fov, width: width, height: height) {
            fovH = pair.h
            fovV = pair.v
        }

        // Pass 1: pixels upright + EXIF/GPS/TIFF.
        var props = original
        props.removeValue(forKey: kCGImagePropertyPixelWidth as String)
        props.removeValue(forKey: kCGImagePropertyPixelHeight as String)
        props.removeValue(forKey: kCGImagePropertyMakerAppleDictionary as String)
        props[kCGImagePropertyOrientation as String] = 1

        var tiff = (props[kCGImagePropertyTIFFDictionary as String] as? [String: Any]) ?? [:]
        tiff[kCGImagePropertyTIFFOrientation as String] = 1
        props[kCGImagePropertyTIFFDictionary as String] = tiff

        var exif = exifIn
        exif[kCGImagePropertyExifPixelXDimension as String] = width
        exif[kCGImagePropertyExifPixelYDimension as String] = height
        let local = localTimestamp(input.capturedAt, timeZone: input.timeZone)
        exif[kCGImagePropertyExifDateTimeOriginal as String] = local.dateTime
        exif[kCGImagePropertyExifDateTimeDigitized as String] = local.dateTime
        exif[kCGImagePropertyExifSubsecTimeOriginal as String] = local.subsec
        // Literal keys (EXIF 2.31 offset tags) to avoid SDK constant availability questions.
        exif["OffsetTimeOriginal"] = local.offset
        exif["OffsetTimeDigitized"] = local.offset
        if let focal { exif[kCGImagePropertyExifFocalLength as String] = focal }
        if let focal35 { exif[kCGImagePropertyExifFocalLenIn35mmFilm as String] = Int(focal35.rounded()) }
        props[kCGImagePropertyExifDictionary as String] = exif

        props[kCGImagePropertyGPSDictionary as String] = gpsDictionary(input)
        props[kCGImageDestinationLossyCompressionQuality as String] = 0.92

        let pass1 = NSMutableData()
        guard let dest1 = CGImageDestinationCreateWithData(pass1 as CFMutableData, jpegType, 1, nil) else {
            throw PhotoProcessingError.encodeFailed
        }
        CGImageDestinationAddImage(dest1, upright, props as CFDictionary)
        guard CGImageDestinationFinalize(dest1) else { throw PhotoProcessingError.encodeFailed }

        // Pass 2: XMP. If it fails, the file still carries EXIF/GPS.
        let output = addXMP(to: pass1 as Data, input: input, fovH: fovH, fovV: fovV) ?? (pass1 as Data)

        return ProcessedPhoto(
            jpeg: output,
            thumbnail: thumbnail(from: source),
            width: width,
            height: height,
            focalLengthMm: focal,
            focalLength35mm: focal35,
            fovH: fovH,
            fovV: fovV
        )
    }

    static func gpsDictionary(_ input: CaptureInput) -> [String: Any] {
        var gps: [String: Any] = [:]
        gps[kCGImagePropertyGPSLatitude as String] = abs(input.latitude)
        gps[kCGImagePropertyGPSLatitudeRef as String] = input.latitude >= 0 ? "N" : "S"
        gps[kCGImagePropertyGPSLongitude as String] = abs(input.longitude)
        gps[kCGImagePropertyGPSLongitudeRef as String] = input.longitude >= 0 ? "E" : "W"
        if let alt = input.mslAltitude, alt.isFinite {
            gps[kCGImagePropertyGPSAltitude as String] = abs(alt)
            gps[kCGImagePropertyGPSAltitudeRef as String] = alt < 0 ? 1 : 0
        }
        if let acc = input.horizontalAccuracy, acc >= 0 {
            gps["HPositioningError"] = acc // kCGImagePropertyGPSHPositioningError
        }
        if let heading = input.heading, heading.isFinite {
            gps[kCGImagePropertyGPSImgDirection as String] = heading
            gps[kCGImagePropertyGPSImgDirectionRef as String] = "T"
        }
        let utc = utcStamps(input.capturedAt)
        gps[kCGImagePropertyGPSTimeStamp as String] = utc.time
        gps[kCGImagePropertyGPSDateStamp as String] = utc.date
        gps[kCGImagePropertyGPSMapDatum as String] = "WGS-84"
        return gps
    }

    static func addXMP(to jpeg: Data, input: CaptureInput, fovH: Double?, fovV: Double?) -> Data? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let metadata = CGImageMetadataCreateMutable()
        guard CGImageMetadataRegisterNamespaceForPrefix(metadata, xmpNamespace as CFString, xmpPrefix as CFString, nil) else {
            return nil
        }
        let values: [(String, Double?)] = [
            ("Pitch", input.pitch),
            ("Roll", input.roll),
            ("HeadingAccuracy", input.headingAccuracy),
            ("FovH", fovH),
            ("FovV", fovV),
        ]
        var wroteAny = false
        for (name, value) in values {
            guard let value, value.isFinite else { continue }
            let path = "\(xmpPrefix):\(name)" as CFString
            let text = String(format: "%.4f", value) as CFString
            if CGImageMetadataSetValueWithPath(metadata, nil, path, text) { wroteAny = true }
        }
        guard wroteAny else { return nil }

        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, jpegType, 1, nil) else { return nil }
        let options: [String: Any] = [
            kCGImageDestinationMetadata as String: metadata,
            kCGImageDestinationMergeMetadata as String: true,
        ]
        // CopyImageSource finalizes the destination itself; no Finalize call.
        guard CGImageDestinationCopyImageSource(dest, source, options as CFDictionary, nil) else { return nil }
        return out as Data
    }

    static func thumbnail(from source: CGImageSource, maxPixel: Int = 300) -> Data? {
        let options: [String: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: maxPixel,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, jpegType, 1, nil) else { return nil }
        let props: [String: Any] = [kCGImageDestinationLossyCompressionQuality as String: 0.7]
        CGImageDestinationAddImage(dest, image, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    // MARK: Time formatting

    static func localTimestamp(_ date: Date, timeZone: TimeZone) -> (dateTime: String, subsec: String, offset: String) {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = timeZone
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        let dateTime = f.string(from: date)
        let millis = Int((date.timeIntervalSince1970 * 1000).rounded()) % 1000
        let subsec = String(format: "%03d", (millis + 1000) % 1000)
        let seconds = timeZone.secondsFromGMT(for: date)
        let sign = seconds < 0 ? "-" : "+"
        let absSeconds = abs(seconds)
        let offset = sign + String(format: "%02d:%02d", absSeconds / 3600, (absSeconds % 3600) / 60)
        return (dateTime, subsec, offset)
    }

    static func utcStamps(_ date: Date) -> (date: String, time: String) {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.dateFormat = "yyyy:MM:dd"
        let d = f.string(from: date)
        f.dateFormat = "HH:mm:ss.SS"
        let t = f.string(from: date)
        return (d, t)
    }
}
