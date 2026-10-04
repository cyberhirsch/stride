import Foundation
import AVFoundation
import CoreMedia
import UIKit

enum CameraError: LocalizedError {
    case notAuthorized
    case noCamera
    case configurationFailed
    case notRunning
    case captureFailed

    var errorDescription: String? {
        switch self {
        case .notAuthorized: return "Camera access is not allowed. Enable it in Settings."
        case .noCamera: return "No back camera available."
        case .configurationFailed: return "Could not configure the camera."
        case .notRunning: return "The camera is not running."
        case .captureFailed: return "The photo could not be captured."
        }
    }
}

/// Optics of the active camera format at shutter time.
struct OpticsInfo {
    /// Horizontal FOV of the active format in degrees. Applies to the sensor's
    /// long side (the format is landscape), before zoom.
    let videoFieldOfView: Double
    let zoomFactor: Double
    let formatWidth: Int
    let formatHeight: Int
    let deviceType: String

    /// Long-side FOV after digital zoom.
    var effectiveLongSideFov: Double {
        OrientationMath.zoomedFov(videoFieldOfView, zoom: zoomFactor)
    }
}

@MainActor
final class CameraController: ObservableObject {
    @Published private(set) var isAuthorized = false
    @Published private(set) var authorizationChecked = false
    @Published private(set) var isRunning = false
    @Published private(set) var isCapturing = false
    @Published private(set) var errorMessage: String?

    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private let sessionQueue = DispatchQueue(label: "app.stride.camera.session")
    private var configured = false
    private var inFlight: [Int64: PhotoCaptureDelegate] = [:]

    func start() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            isAuthorized = true
        case .notDetermined:
            isAuthorized = await AVCaptureDevice.requestAccess(for: .video)
        default:
            isAuthorized = false
        }
        authorizationChecked = true
        guard isAuthorized else {
            errorMessage = CameraError.notAuthorized.errorDescription
            return
        }
        if !configured {
            do {
                try configure()
            } catch {
                errorMessage = error.localizedDescription
                return
            }
        }
        let session = self.session
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            sessionQueue.async {
                if !session.isRunning { session.startRunning() }
                cont.resume()
            }
        }
        isRunning = session.isRunning
        errorMessage = nil
    }

    func stop() {
        let session = self.session
        sessionQueue.async {
            if session.isRunning { session.stopRunning() }
        }
        isRunning = false
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        // The plain wide-angle camera (not a virtual multi-camera device) so the
        // field of view never changes behind our back.
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw CameraError.noCamera
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CameraError.configurationFailed }
        session.addInput(input)
        guard session.canAddOutput(photoOutput) else { throw CameraError.configurationFailed }
        session.addOutput(photoOutput)
        photoOutput.maxPhotoQualityPrioritization = .quality
        self.device = device
        configured = true
    }

    func currentOptics() -> OpticsInfo? {
        guard let device else { return nil }
        let format = device.activeFormat
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return OpticsInfo(
            videoFieldOfView: Double(format.videoFieldOfView),
            zoomFactor: Double(device.videoZoomFactor),
            formatWidth: Int(dims.width),
            formatHeight: Int(dims.height),
            deviceType: device.deviceType.rawValue
        )
    }

    /// Captures one JPEG. `rotationAngle` is the AVCaptureConnection video
    /// rotation angle (0/90/180/270) chosen from the device orientation.
    func capturePhoto(rotationAngle: Double) async throws -> Data {
        guard configured, session.isRunning else { throw CameraError.notRunning }

        let settings: AVCapturePhotoSettings
        if photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
            settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        } else {
            settings = AVCapturePhotoSettings()
        }
        settings.photoQualityPrioritization = .quality

        if let connection = photoOutput.connection(with: .video) {
            let angle = CGFloat(rotationAngle)
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        }

        isCapturing = true
        defer { isCapturing = false }

        let id = settings.uniqueID
        let output = photoOutput
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            let delegate = PhotoCaptureDelegate { [weak self] result in
                cont.resume(with: result)
                Task { @MainActor in self?.inFlight[id] = nil }
            }
            inFlight[id] = delegate
            output.capturePhoto(with: settings, delegate: delegate)
        }
    }
}

/// Collects the photo data; the controller keeps it alive until completion.
final class PhotoCaptureDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    private let completion: @Sendable (Result<Data, Error>) -> Void
    private var data: Data?
    private var processingError: Error?

    init(completion: @escaping @Sendable (Result<Data, Error>) -> Void) {
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            processingError = error
            return
        }
        data = photo.fileDataRepresentation()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        if let data {
            completion(.success(data))
        } else {
            completion(.failure(error ?? processingError ?? CameraError.captureFailed))
        }
    }
}
