package com.labteto.dshmobile.ui.screens.pair

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.FontSizeOption
import com.labteto.dshmobile.ui.theme.ThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// The scanner is a camera surface, not a themed screen: its furniture has to be legible on top of
// whatever the lens is pointing at. It keeps its dark palette in every app theme by re-providing the
// dark colours locally (see [QrScanSheet]) rather than by using hand-picked whites, so an accent,
// text size or haptics choice still reaches it.
private val FrameRadius = 26.dp
private val FrameCornerStroke = 4.dp

/**
 * The camera sheet that reads a relay's pairing QR.
 *
 * Built to feel like the scan screen of a bank app, because that is the shape people already know:
 * one live camera, a frame to aim at, a flashlight, a way to read a code out of a screenshot, and a
 * way out that does not require the camera at all. Every one of those exits exists because the
 * pairing code is the only route into a relay — a scanner that silently does nothing is a dead end,
 * which is exactly the failure this screen replaces.
 *
 * The composition runs under a forced dark [DshTheme]. The camera chrome must stay dark whichever
 * theme the app is in — a Paper-theme white top bar over a live camera cannot be read — but the
 * colours are still the Ink tokens (dark scheme), so the accent, text size and haptics settings the
 * user picked all still apply.
 *
 * Motion here is limited to one looping element (the laser line), and only while the camera is live:
 * it is the same signal a loading skeleton gives — "something is happening" — and stops the moment
 * a code is read, the camera pauses, or the user leaves.
 */
@Composable
fun QrScanSheet(
    onClose: () -> Unit,
    onDecoded: (String) -> Unit,
    onTypeCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appearance = DsTheme.appearance
    DshTheme(
        // Forced dark, always: a Paper-theme top bar over a live camera cannot be read. The font
        // size goes back to Default because the outer theme has already scaled the density — keeping
        // the user's multiplier here would apply it a second time inside the camera chrome.
        customization = appearance.copy(
            preference = ThemePreference.DARK,
            amoled = false,
            fontSize = FontSizeOption.Default,
        ),
    ) {
        QrScanSurface(
            onClose = onClose,
            onDecoded = onDecoded,
            onTypeCode = onTypeCode,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun QrScanSurface(
    onClose: () -> Unit,
    onDecoded: (String) -> Unit,
    onTypeCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    val scrollScope = rememberCoroutineScope()
    val permission = Manifest.permission.CAMERA

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedAtLeastOnce by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var cameraStarted by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var successText by remember { mutableStateOf<String?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoProblem by remember { mutableStateOf<Int?>(null) }

    val decodedOnce = remember { AtomicBoolean(false) }
    val barcodeView = remember { AtomicReference<BarcodeView?>(null) }
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val hasTorch = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }
    val cameraLive = granted && hasCamera && !cameraFailed

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        blocked = !allowed && activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
        } == true
    }

    // Ask on arrival, like the bank apps do, rather than after a second tap: the person tapped
    // "Scan QR code" once and that is the intent. A refusal is handled below, in the sheet.
    LaunchedEffect(Unit) {
        if (hasCamera && !granted && !askedAtLeastOnce) {
            askedAtLeastOnce = true
            permissionLauncher.launch(permission)
        }
    }

    // Coming back from the system settings page or the photo picker must re-read the grant and
    // reopen the lens — the picker and the Settings page both take the camera away from this
    // activity, and a preview left paused on return is a black rectangle with no explanation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    granted = ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
                    // A camera that failed while another activity held it gets one more chance.
                    cameraFailed = false
                    barcodeView.get()?.resume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    cameraStarted = false
                    barcodeView.get()?.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One edge-to-edge camera screen needs three window adjustments, all undone on the way out:
    // white status/navigation glyphs (the app's light theme would draw dark ones on a dark surface),
    // and the screen kept awake while the lens is open.
    DisposableEffect(view, activity) {
        val window = activity?.window
        // The decor view, not the Compose view: a rotation recreates the window and the framework
        // re-applies the light-theme bar appearance to it, which left dark glyphs on a dark camera
        // surface until the next composition touched them.
        val decorations = window?.decorView
        val controller = decorations?.let { WindowCompat.getInsetsController(window, it) }
        val lightStatusBars = controller?.isAppearanceLightStatusBars
        val lightNavigationBars = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lightStatusBars?.let { controller?.isAppearanceLightStatusBars = it }
            lightNavigationBars?.let { controller?.isAppearanceLightNavigationBars = it }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun accept(text: String) {
        // The camera callback fires several times a second; the first read wins and the rest are
        // dropped, so the claim is never posted twice for one code.
        if (!decodedOnce.compareAndSet(false, true)) return
        haptics.confirm()
        successText = text
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoBusy = true
        photoProblem = null
        scrollScope.launch {
            when (val scan = readQrFromPhoto(context.applicationContext, uri)) {
                is PhotoScan.Read -> accept(scan.text)
                PhotoScan.NoCode -> {
                    photoProblem = R.string.scan_photo_empty
                    haptics.reject()
                }
                PhotoScan.Unreadable -> {
                    photoProblem = R.string.scan_photo_unreadable
                    haptics.reject()
                }
            }
            photoBusy = false
        }
    }

    // The read is announced on the frame for a beat before the sheet leaves, so the person sees the
    // scan land instead of the screen simply vanishing under their hand.
    LaunchedEffect(successText) {
        val text = successText ?: return@LaunchedEffect
        delay(620)
        onDecoded(text)
    }
    LaunchedEffect(photoProblem) {
        if (photoProblem != null) {
            delay(4500)
            photoProblem = null
        }
    }
    LaunchedEffect(torchOn, cameraStarted) {
        if (!cameraStarted) return@LaunchedEffect
        runCatching { barcodeView.get()?.setTorch(torchOn) }
    }

    BackHandler { onClose() }

    BoxWithConstraints(
        modifier = modifier
            .background(colors.bgBase),
    ) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            ScannerTopBar(
                title = stringResource(R.string.pair_title),
                torchOn = torchOn,
                torchAvailable = hasTorch && cameraLive,
                onClose = onClose,
                onToggleTorch = {
                    haptics.toggle(!torchOn)
                    torchOn = !torchOn
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cameraLive) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            BarcodeView(ctx).apply {
                                // A TextureView, not a SurfaceView: a SurfaceView is composited in its
                                // own window layer, above every Compose overlay, which would hide the
                                // frame and the buttons behind the camera feed.
                                setUseTextureView(true)
                                setDecoderFactory(
                                    DefaultDecoderFactory(
                                        listOf(BarcodeFormat.QR_CODE),
                                        mapOf(DecodeHintType.TRY_HARDER to true),
                                        null,
                                        0,
                                    ),
                                )
                                addStateListener(
                                    object : CameraPreview.StateListener {
                                        override fun previewSized() = Unit
                                        override fun previewStarted() {
                                            cameraStarted = true
                                        }
                                        override fun previewStopped() {
                                            cameraStarted = false
                                        }
                                        override fun cameraClosed() {
                                            cameraStarted = false
                                        }
                                        override fun cameraError(error: Exception) {
                                            cameraFailed = true
                                        }
                                    },
                                )
                                decodeContinuous(
                                    object : BarcodeCallback {
                                        override fun barcodeResult(result: BarcodeResult) {
                                            result.text?.let(::accept)
                                        }
                                    },
                                )
                                post { resume() }
                            }.also { barcodeView.set(it) }
                        },
                        onRelease = {
                            barcodeView.set(null)
                            cameraStarted = false
                            it.pause()
                            it.stopDecoding()
                        },
                    )
                    Viewfinder(
                        modifier = Modifier.fillMaxSize(),
                        landscape = landscape,
                        detected = successText != null,
                    )
                } else {
                    CameraNotice(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = DsSpacing.xlarge),
                        title = when {
                            !hasCamera -> stringResource(R.string.scan_camera_missing)
                            cameraFailed -> stringResource(R.string.scan_camera_title)
                            else -> stringResource(R.string.scan_camera_title)
                        },
                        body = when {
                            cameraFailed -> stringResource(R.string.scan_camera_failed)
                            blocked -> stringResource(R.string.scan_camera_denied)
                            !hasCamera -> stringResource(R.string.scan_camera_body)
                            else -> stringResource(R.string.scan_camera_body)
                        },
                        primaryLabel = when {
                            !hasCamera -> null
                            cameraFailed -> stringResource(R.string.common_retry)
                            blocked -> stringResource(R.string.scan_camera_settings)
                            else -> stringResource(R.string.scan_camera_allow)
                        },
                        onPrimary = when {
                            !hasCamera -> null
                            cameraFailed -> ({
                                cameraFailed = false
                                cameraStarted = false
                            })
                            blocked -> ({ openAppSettings(context) })
                            else -> ({ permissionLauncher.launch(permission) })
                        },
                        secondaryLabel = stringResource(R.string.scan_type_instead),
                        onSecondary = onTypeCode,
                    )
                }
            }

            if (cameraLive) {
                ScannerHint(
                    text = if (successText != null) {
                        stringResource(R.string.scan_detected)
                    } else if (photoBusy) {
                        stringResource(R.string.scan_photo_working)
                    } else {
                        photoProblem?.let { stringResource(it) } ?: stringResource(R.string.scan_hint)
                    },
                    tone = when {
                        successText != null -> ScannerHintTone.Success
                        photoProblem != null -> ScannerHintTone.Error
                        else -> ScannerHintTone.Normal
                    },
                )
                if (successText == null) {
                    ScannerActions(
                        landscape = landscape,
                        photoBusy = photoBusy,
                        onPhoto = {
                            haptics.tap()
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onTypeCode = {
                            haptics.tap()
                            onTypeCode()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The live camera area of the screen: a rounded outline with four corner brackets, the sweeping
 * laser while looking, and a success badge once a code is read.
 *
 * Everything is sized from the space this composable is handed, not from a percentage of the screen,
 * which is what keeps the frame, the hint and the buttons from ever sharing pixels in landscape.
 */
@Composable
private fun Viewfinder(
    modifier: Modifier = Modifier,
    landscape: Boolean,
    detected: Boolean,
) {
    val colors = DsTheme.colors
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxFrame = minOf(
            maxWidth * if (landscape) 0.42f else 0.80f,
            maxHeight * if (landscape) 0.92f else 0.94f,
            320.dp,
        ).coerceAtLeast(120.dp)

        val laser = rememberInfiniteTransition(label = "scanLaser")
        val laserProgress by laser.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "laserProgress",
        )
        val successScale by animateFloatAsState(
            targetValue = if (detected) 1f else 0.6f,
            animationSpec = DsAnimations.fastSpring,
            label = "scanSuccess",
        )

        Box(
            modifier = Modifier.size(maxFrame),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 1.dp.toPx()
                val radius = CornerRadius(FrameRadius.toPx(), FrameRadius.toPx())
                drawRoundRect(
                    color = colors.borderL3,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx()),
                )

                // Corner brackets: a bank-app scanner's one unmistakable cue. They sit on the edge
                // with a gap at the corner itself so the rounded outline still reads.
                val bracket = if (detected) colors.success else colors.labelPrimary
                val arm = minOf(size.width * 0.17f, 58.dp.toPx())
                val gap = FrameRadius.toPx() * 0.45f
                val stroke = FrameCornerStroke.toPx()
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

                // The one looping element on the screen. Hidden the instant a code is read, so it
                // never reads as "still looking" over a frame that is already done.
                if (!detected) {
                    val insetPx = 18.dp.toPx()
                    val travelTop = insetPx
                    val travelBottom = size.height - insetPx
                    val y = travelTop + (travelBottom - travelTop) * laserProgress
                    val startX = insetPx
                    val endX = size.width - insetPx
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, colors.accent.copy(alpha = 0.30f), Color.Transparent),
                            startX,
                            endX,
                        ),
                        topLeft = Offset(startX, y - 8.dp.toPx()),
                        size = Size(endX - startX, 16.dp.toPx()),
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, colors.accent.copy(alpha = 0.95f), Color.Transparent),
                            startX,
                            endX,
                        ),
                        topLeft = Offset(startX, y - 1.dp.toPx()),
                        size = Size(endX - startX, 2.dp.toPx()),
                    )
                }
            }
            if (detected) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer {
                            scaleX = successScale
                            scaleY = successScale
                        }
                        .background(colors.success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = colors.onBrandPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

private enum class ScannerHintTone { Normal, Success, Error }

/** The status line under the viewfinder: looking, working on a photo, read, or a photo problem. */
@Composable
private fun ScannerHint(text: String, tone: ScannerHintTone) {
    val colors = DsTheme.colors
    Text(
        text = text,
        style = DsType.small13Strong,
        color = when (tone) {
            ScannerHintTone.Success -> colors.success
            ScannerHintTone.Error -> colors.error
            ScannerHintTone.Normal -> colors.labelSecondary
        },
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.small),
    )
}

@Composable
private fun ScannerTopBar(
    title: String,
    torchOn: Boolean,
    torchAvailable: Boolean,
    onClose: () -> Unit,
    onToggleTorch: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScannerIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            onClick = onClose,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                title,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (torchAvailable) {
            ScannerIconButton(
                icon = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = stringResource(
                    if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on,
                ),
                onClick = onToggleTorch,
                active = torchOn,
            )
        } else {
            Spacer(Modifier.size(DsSpacing.touchTarget))
        }
    }
}

/**
 * A round glass control for the camera surface. Deliberately not [DsIconButton]: that one is drawn
 * for app panels and its hover-facing fill is wrong over a live camera, where the control needs a
 * translucent dark disc of its own.
 */
@Composable
private fun ScannerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Surface(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = modifier.size(DsSpacing.touchTarget),
        shape = CircleShape,
        color = when {
            active -> colors.brandPrimary
            else -> colors.bgLayer2.copy(alpha = 0.72f)
        },
        contentColor = if (active) colors.onBrandPrimary else colors.labelPrimary,
        border = if (active) null else BorderStroke(1.dp, colors.borderL3),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) colors.onBrandPrimary else colors.labelPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ScannerActions(
    landscape: Boolean,
    photoBusy: Boolean,
    onPhoto: () -> Unit,
    onTypeCode: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = DsSpacing.xlarge,
                end = DsSpacing.xlarge,
                top = DsSpacing.small,
                bottom = if (landscape) DsSpacing.small else DsSpacing.medium,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (landscape) {
            // Rotated, the two actions sit side by side: stacked they consumed the height the
            // viewfinder needed and left the hint underneath them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                PhotoAction(
                    photoBusy = photoBusy,
                    onPhoto = onPhoto,
                    modifier = Modifier.weight(1f),
                )
                TypeCodeAction(onTypeCode = onTypeCode, modifier = Modifier.weight(1f))
            }
        } else {
            PhotoAction(photoBusy = photoBusy, onPhoto = onPhoto, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(DsSpacing.small))
            TypeCodeAction(onTypeCode = onTypeCode, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhotoAction(
    photoBusy: Boolean,
    onPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onPhoto,
        enabled = !photoBusy,
        modifier = modifier.height(52.dp),
        shape = DsShapes.buttonLarge,
        color = colors.bgLayer2,
        contentColor = colors.labelPrimary,
        border = BorderStroke(1.dp, colors.borderL3),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DsSpacing.large),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photoBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.labelPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                text = stringResource(
                    if (photoBusy) R.string.scan_photo_working else R.string.scan_from_photo,
                ),
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TypeCodeAction(
    onTypeCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onTypeCode,
        modifier = modifier.height(52.dp),
        shape = DsShapes.buttonLarge,
        color = Color.Transparent,
        contentColor = colors.labelPrimary,
        border = BorderStroke(1.dp, colors.borderL3),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.scan_type_instead),
                style = DsType.small13Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shown instead of the camera whenever there is nothing to point it at: permission refused, no
 * camera on the device, or the camera failing to open. Each names the actual cause and offers the
 * one action that resolves it — plus the typed code, which always works.
 */
@Composable
private fun CameraNotice(
    title: String,
    body: String,
    primaryLabel: String?,
    onPrimary: (() -> Unit)?,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.xlarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(colors.bgLayer3, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(DsSpacing.medium))
            Text(
                text = title,
                style = DsType.base16Strong,
                color = colors.labelPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DsSpacing.small))
            Text(
                text = body,
                style = DsType.small13,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
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
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(
                    onClick = onSecondary,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.labelSecondary),
                ) {
                    Text(secondaryLabel, style = DsType.small13Strong)
                }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
