import SwiftUI
import UIKit
import AVFoundation

/// AVCaptureVideoPreviewLayer hosted in SwiftUI. The app UI is portrait-only,
/// so the preview is always rotated for portrait (90 degrees for the back camera).
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspect
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.applyPortraitRotation()
    }
}

final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // layerClass guarantees the type.
        layer as! AVCaptureVideoPreviewLayer
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        applyPortraitRotation()
    }

    func applyPortraitRotation() {
        guard let connection = previewLayer.connection else { return }
        if connection.isVideoRotationAngleSupported(90) && connection.videoRotationAngle != 90 {
            connection.videoRotationAngle = 90
        }
    }
}
