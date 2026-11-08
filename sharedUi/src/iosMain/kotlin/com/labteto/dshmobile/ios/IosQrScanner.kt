package com.labteto.dshmobile.ios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_back
import com.labteto.dshmobile.shared.resources.common_retry
import com.labteto.dshmobile.shared.resources.pair_title
import com.labteto.dshmobile.shared.resources.scan_camera_allow
import com.labteto.dshmobile.shared.resources.scan_camera_body
import com.labteto.dshmobile.shared.resources.scan_camera_denied
import com.labteto.dshmobile.shared.resources.scan_camera_failed
import com.labteto.dshmobile.shared.resources.scan_camera_missing
import com.labteto.dshmobile.shared.resources.scan_camera_settings
import com.labteto.dshmobile.shared.resources.scan_camera_title
import com.labteto.dshmobile.shared.resources.scan_detected
import com.labteto.dshmobile.shared.resources.scan_hint
import com.labteto.dshmobile.shared.resources.scan_type_instead
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.FontSizeOption
import com.labteto.dshmobile.ui.theme.ThemePreference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.resume
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/*
 * The iOS half of the Pair screen's scanner (`PairScanner`): a camera sheet that reads a relay's pairing QR.
 *
 * Android's is `QrScanSheet` in `:app` (ZXing on a CameraX-like preview, the camera permission, the
 * flashlight, a photo picker). This one is AVFoundation: an `AVCaptureSession` with an
 * `AVCaptureMetadataOutput` set to `.qr`, shown through an `AVCaptureVideoPreviewLayer` inside a `UIView`
 * that Compose hosts with `UIKitView`. The camera permission string is `NSCameraUsageDescription` in the
 * Info.plist (translated in every `InfoPlist.strings`).
 *
 * The chrome is the same idea as Android's (a dark sheet, a frame to aim at, one status line, a way out
 * that needs no camera) and uses the same strings, but not every part: no flashlight and no "choose a
 * photo" (iOS would need a PHPicker and a Core Image detector for it), and the frame does not sweep a laser.
 * Those are parity gaps listed in docs/IOS.md, not oversights.
 *
 * **The simulator has no camera.** `AVCaptureDevice.defaultDevice(for: .video)` is nil there, the sheet shows
 * its "no camera" notice with the typed code as the way on, and the CI scenarios pair through the typed form
 * and through a debug launch argument that hands the controller the text a scan would have produced. What is
 * therefore not exercised anywhere but a phone: the permission prompt, the preview and the decode.
 */

/** What the app may do with the camera right now. */
private enum class CameraAccess { NoCamera, Undecided, Granted, Denied }

private fun currentCameraAccess(): CameraAccess {
    if (AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) == null) return CameraAccess.NoCamera
    return when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> CameraAccess.Granted
        AVAuthorizationStatusNotDetermined -> CameraAccess.Undecided
        else -> CameraAccess.Denied
    }
}

/** Shows the system's camera permission prompt and says what the person answered. */
private suspend fun requestCameraAccess(): CameraAccess = suspendCancellableCoroutine { continuation ->
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        continuation.resume(if (granted) CameraAccess.Granted else CameraAccess.Denied)
    }
}

private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

/**
 * The camera sheet. Forced dark, as Android's is: a light top bar over a live camera cannot be read, and the
 * user's accent and haptics settings still apply because the colours are the same Ink tokens.
 */
@Composable
internal fun IosQrScanner(onClose: () -> Unit, onDecoded: (String) -> Unit, onTypeCode: () -> Unit) {
    val appearance = DsTheme.appearance
    DshTheme(
        customization = appearance.copy(
            preference = ThemePreference.DARK,
            amoled = false,
            // The outer theme has already scaled the density; keeping the multiplier would apply it twice.
            fontSize = FontSizeOption.Default,
        ),
    ) {
        ScannerSurface(onClose = onClose, onDecoded = onDecoded, onTypeCode = onTypeCode)
    }
}

@Composable
private fun ScannerSurface(onClose: () -> Unit, onDecoded: (String) -> Unit, onTypeCode: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(currentCameraAccess()) }
    var cameraFailed by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<String?>(null) }

    // Ask on arrival: the person tapped "Scan QR code" once and that is the intent. A refusal is handled below.
    LaunchedEffect(Unit) {
        if (access == CameraAccess.Undecided) access = requestCameraAccess()
    }
    // The read is announced on the frame for a beat before the sheet leaves, so the scan visibly lands.
    LaunchedEffect(found) {
        val text = found ?: return@LaunchedEffect
        delay(DETECTED_PAUSE_MS)
        onDecoded(text)
    }

    fun accept(text: String) {
        // The camera reports the same code several times a second; the first one wins and the rest are
        // dropped, so the claim is never posted twice for one scan.
        if (found != null) return
        haptics.confirm()
        found = text
    }

    val cameraLive = access == CameraAccess.Granted && !cameraFailed
    val askAgain: () -> Unit = { scope.launch { access = requestCameraAccess() } }

    Column(Modifier.fillMaxSize().background(colors.bgBase)) {
        ScannerTopBar(title = stringResource(Res.string.pair_title), onClose = onClose)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (cameraLive) {
                QrCameraView(modifier = Modifier.fillMaxSize(), onCode = ::accept, onFailed = { cameraFailed = true })
                Viewfinder(modifier = Modifier.fillMaxSize(), detected = found != null)
            } else {
                CameraNotice(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = DsSpacing.xlarge),
                    title = stringResource(if (access == CameraAccess.NoCamera) Res.string.scan_camera_missing else Res.string.scan_camera_title),
                    body = stringResource(
                        when {
                            cameraFailed -> Res.string.scan_camera_failed
                            access == CameraAccess.Denied -> Res.string.scan_camera_denied
                            else -> Res.string.scan_camera_body
                        },
                    ),
                    primaryLabel = when {
                        access == CameraAccess.NoCamera -> null
                        cameraFailed -> stringResource(Res.string.common_retry)
                        access == CameraAccess.Denied -> stringResource(Res.string.scan_camera_settings)
                        else -> stringResource(Res.string.scan_camera_allow)
                    },
                    onPrimary = when {
                        access == CameraAccess.NoCamera -> null
                        cameraFailed -> ({ cameraFailed = false })
                        access == CameraAccess.Denied -> ::openAppSettings
                        // Still undecided: the prompt was dismissed without an answer, so ask again.
                        else -> askAgain
                    },
                    secondaryLabel = stringResource(Res.string.scan_type_instead),
                    onSecondary = onTypeCode,
                )
            }
        }

        if (cameraLive) {
            Text(
                text = stringResource(if (found != null) Res.string.scan_detected else Res.string.scan_hint),
                style = DsType.small13Strong,
                color = if (found != null) colors.success else colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.small),
            )
            if (found == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = DsSpacing.xlarge, end = DsSpacing.xlarge, top = DsSpacing.small, bottom = DsSpacing.medium),
                ) {
                    TypeCodeAction(
                        onClick = {
                            haptics.tap()
                            onTypeCode()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** How long "Code read" stays on the frame before the sheet closes. */
private const val DETECTED_PAUSE_MS = 620L

@Composable
private fun ScannerTopBar(title: String, onClose: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A glass disc, like Android's: a translucent dark control over whatever the lens is pointing at.
        Box(
            modifier = Modifier
                .size(DsSpacing.touchTarget)
                .clip(CircleShape)
                .background(colors.bgLayer2.copy(alpha = 0.72f), CircleShape)
                .border(BorderStroke(1.dp, colors.borderL3), CircleShape)
                .clickable {
                    haptics.tap()
                    onClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DsIcons.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = colors.labelPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(title, style = DsType.std14Strong, color = colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Where Android puts the flashlight: kept as space so the title stays centred.
        Spacer(Modifier.size(DsSpacing.touchTarget))
    }
}

@Composable
private fun TypeCodeAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(DsShapes.buttonLarge)
            .border(BorderStroke(1.dp, colors.borderL3), DsShapes.buttonLarge)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.scan_type_instead),
            style = DsType.small13Strong,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shown instead of the camera whenever there is nothing to point it at: no camera on the device (every
 * simulator), permission refused or not yet given, or the session failing to start. Each names the actual
 * cause and offers the one action that resolves it, plus the typed code, which always works.
 */
@Composable
private fun CameraNotice(
    title: String,
    body: String,
    primaryLabel: String?,
    onPrimary: (() -> Unit)?,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.bgLayer2)
            .border(BorderStroke(1.dp, colors.borderL2), RoundedCornerShape(24.dp))
            .padding(DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).background(colors.bgLayer3, CircleShape), contentAlignment = Alignment.Center) {
            Icon(DsIcons.QrCodeScanner, contentDescription = null, tint = colors.labelPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(DsSpacing.medium))
        Text(title, style = DsType.base16Strong, color = colors.labelPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(DsSpacing.small))
        Text(body, style = DsType.small13, color = colors.labelSecondary, textAlign = TextAlign.Center)
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(DsSpacing.large))
            DsButton(
                text = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Primary,
                size = DsButtonSize.Large,
            )
        }
        Spacer(Modifier.height(DsSpacing.small))
        Box(Modifier.clip(DsShapes.buttonLarge).clickable(onClick = onSecondary).padding(DsSpacing.medium)) {
            Text(secondaryLabel, style = DsType.small13Strong, color = colors.labelSecondary)
        }
    }
}

/**
 * The aiming frame: a rounded outline with four corner brackets, and a round badge once a code is read.
 * Sized from the space it is given, as Android's is, so it never shares pixels with the hint below it.
 */
@Composable
private fun Viewfinder(modifier: Modifier = Modifier, detected: Boolean) {
    val colors = DsTheme.colors
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val frame = minOf(maxWidth * 0.80f, maxHeight * 0.94f, 320.dp).coerceAtLeast(120.dp)
        Box(Modifier.size(frame), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = 26.dp.toPx()
                val inset = 1.dp.toPx()
                drawRoundRect(
                    color = colors.borderL3,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 1.dp.toPx()),
                )
                val bracket = if (detected) colors.success else colors.labelPrimary
                val arm = minOf(size.width * 0.17f, 58.dp.toPx())
                val gap = radius * 0.45f
                val stroke = 4.dp.toPx()
                fun corner(x: Float, y: Float, horizontal: Float, vertical: Float) {
                    drawLine(
                        color = bracket,
                        start = Offset(x + horizontal * gap, y + vertical * gap),
                        end = Offset(x + horizontal * (gap + arm), y + vertical * gap),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = bracket,
                        start = Offset(x + horizontal * gap, y + vertical * gap),
                        end = Offset(x + horizontal * gap, y + vertical * (gap + arm)),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                corner(0f, 0f, 1f, 1f)
                corner(size.width, 0f, -1f, 1f)
                corner(0f, size.height, 1f, -1f)
                corner(size.width, size.height, -1f, -1f)
            }
            if (detected) {
                Box(Modifier.size(60.dp).background(colors.success, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(DsIcons.Check, contentDescription = null, tint = colors.onBrandPrimary, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

/**
 * The live preview, hosted in Compose.
 *
 * The session is started in [UIKitView]'s factory and stopped when the view is released; `startRunning` blocks
 * for as long as the hardware takes, so it runs off the main thread. A session that cannot be built (no
 * device, an input or output the session refuses) is reported through [onFailed] from an effect, never from
 * inside composition.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun QrCameraView(modifier: Modifier, onCode: (String) -> Unit, onFailed: () -> Unit) {
    val camera = remember { QrCamera(onCode) }
    DisposableEffect(camera) { onDispose { camera.stop() } }
    UIKitView(
        factory = {
            camera.start()
            camera.view
        },
        modifier = modifier,
        onRelease = { camera.stop() },
    )
    LaunchedEffect(camera) {
        if (!camera.started) onFailed()
    }
}

/**
 * The view the preview layer lives in. Compose sets the view's frame as the layout changes (CMP 1.7 has no
 * resize callback), and a layer's frame does not follow its view, so the layer is fitted here, without the
 * implicit animation a layer's frame change would get.
 */
@OptIn(ExperimentalForeignApi::class)
private class PreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    var previewLayer: AVCaptureVideoPreviewLayer? = null

    override fun layoutSubviews() {
        super.layoutSubviews()
        val layer = previewLayer ?: return
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.frame = bounds
        CATransaction.commit()
    }
}

/** One capture session and the view that shows it. */
@OptIn(ExperimentalForeignApi::class)
private class QrCamera(onCode: (String) -> Unit) {
    val view = PreviewView()

    /** Whether [start] managed to build a session. */
    var started: Boolean = false
        private set

    private var session: AVCaptureSession? = null

    // Held here because the metadata output keeps its delegate weakly.
    private val delegate = QrDelegate(onCode)

    fun start() {
        if (session != null) return
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return
        val captureSession = AVCaptureSession()
        if (!captureSession.canAddInput(input)) return
        captureSession.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (!captureSession.canAddOutput(output)) return
        captureSession.addOutput(output)
        // Delivered on the main queue, where Compose is, so the callback may touch state directly. The type
        // list can only be set after the output is part of a session.
        output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        val layer = AVCaptureVideoPreviewLayer(session = captureSession)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        view.previewLayer = layer
        session = captureSession
        started = true
        dispatch_async(dispatch_get_global_queue(0L, 0UL)) { captureSession.startRunning() }
    }

    fun stop() {
        val running = session ?: return
        session = null
        view.previewLayer?.removeFromSuperlayer()
        view.previewLayer = null
        dispatch_async(dispatch_get_global_queue(0L, 0UL)) { running.stopRunning() }
    }
}

/** Receives what the metadata output recognised; only QR codes are asked for. */
private class QrDelegate(private val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val code = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject ?: return
        code.stringValue?.let(onCode)
    }
}
