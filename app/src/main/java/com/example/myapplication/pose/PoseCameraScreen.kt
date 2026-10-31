package com.example.myapplication.pose

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.exercise.ExerciseMode
import com.example.myapplication.exercise.FeedbackLevel
import com.example.myapplication.exercise.PlankMetrics
import com.example.myapplication.exercise.PlankStatus
import com.example.myapplication.exercise.SquatMetrics
import com.example.myapplication.exercise.formatDuration
import kotlinx.coroutines.awaitCancellation
import kotlin.math.roundToInt

private const val TAG = "PoseCameraScreen"

@Composable
fun PoseCameraScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    /** When set, tracking is fixed to this exercise and the mode selector is hidden. */
    lockedMode: ExerciseMode? = null,
    /** Label shown in place of the mode selector, e.g. "Set 2 of 4 · Squat". */
    title: String? = null,
    viewModel: PoseViewModel = viewModel(),
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var hasRequested by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Ask once automatically on first entry.
    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !hasRequested) {
            hasRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Re-check when returning from system Settings (or if permission was revoked).
    LifecycleResumeEffect(Unit) {
        hasCameraPermission = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    if (hasCameraPermission) {
        PoseCameraContent(
            viewModel = viewModel,
            onClose = onClose,
            lockedMode = lockedMode,
            title = title,
            modifier = modifier,
        )
    } else {
        CameraPermissionRationale(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            onClose = onClose,
            modifier = modifier,
        )
    }
}

@Composable
private fun PoseCameraContent(
    viewModel: PoseViewModel,
    onClose: (() -> Unit)?,
    lockedMode: ExerciseMode?,
    title: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lockedMode) {
        lockedMode?.let(viewModel::selectExercise)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exerciseMode by viewModel.exerciseMode.collectAsStateWithLifecycle()
    // Only the active analyzer emits, so collecting both doesn't cause extra recompositions.
    val squatMetrics by viewModel.squatMetrics.collectAsStateWithLifecycle()
    val plankMetrics by viewModel.plankMetrics.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed: composes correctly with overlays and Compose transforms.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // (Re)binds CameraX whenever the lens or lifecycle owner changes; unbinds on dispose.
    LaunchedEffect(uiState.lensFacing, lifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)

        // Same aspect ratio for both use cases so the overlay mapping matches the preview.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(viewModel.analysisExecutor, viewModel.analyzer) }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(uiState.lensFacing)
            .build()

        try {
            if (!cameraProvider.hasCamera(cameraSelector)) {
                viewModel.reportError("Requested camera is not available on this device")
                return@LaunchedEffect
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera binding failed", e)
            viewModel.reportError("Camera binding failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera binding failed", e)
            viewModel.reportError("Camera binding failed: ${e.message}")
        }

        try {
            awaitCancellation()
        } finally {
            imageAnalysis.clearAnalyzer()
            cameraProvider.unbind(preview, imageAnalysis)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        PoseOverlay(pose = uiState.pose, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClose != null) {
                    FilledTonalButton(onClick = onClose) { Text("End") }
                }
                if (lockedMode == null) {
                    ExerciseModeSelector(
                        selected = exerciseMode,
                        onSelect = viewModel::selectExercise,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = title ?: lockedMode.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                DiagnosticsPanel(uiState = uiState)
                when (exerciseMode) {
                    ExerciseMode.SQUAT -> SquatStatsPanel(metrics = squatMetrics)
                    ExerciseMode.PLANK -> PlankStatsPanel(metrics = plankMetrics)
                }
            }
        }

        if (exerciseMode == ExerciseMode.PLANK) {
            PlankCenterOverlay(metrics = plankMetrics, modifier = Modifier.align(Alignment.Center))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val resetLabel = when (exerciseMode) {
                ExerciseMode.SQUAT -> {
                    FeedbackBanner(text = squatMetrics.feedback, level = squatMetrics.feedbackLevel)
                    "Reset"
                }
                ExerciseMode.PLANK -> {
                    FeedbackBanner(
                        text = plankMetrics.feedback,
                        detail = plankMetrics.detail,
                        level = plankMetrics.feedbackLevel,
                    )
                    if (plankMetrics.status == PlankStatus.FINISHED) "Restart" else "Reset"
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::resetExercise,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text(resetLabel) }
                Button(onClick = viewModel::switchCamera) {
                    Text(
                        if (uiState.lensFacing == CameraSelector.LENS_FACING_FRONT) "Use rear camera"
                        else "Use front camera"
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseModeSelector(
    selected: ExerciseMode,
    onSelect: (ExerciseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ExerciseMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                colors = SegmentedButtonDefaults.colors(
                    inactiveContainerColor = Color.Black.copy(alpha = 0.6f),
                    inactiveContentColor = Color.White,
                ),
            ) {
                Text(mode.label)
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(uiState: PoseUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Delegate: ${uiState.delegate?.name ?: "loading…"}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        uiState.pose?.let {
            Text(
                text = "Inference: ${it.inferenceTimeMs} ms",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        uiState.errorMessage?.let {
            Text(text = it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PlankStatsPanel(metrics: PlankMetrics, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.End,
    ) {
        Text("HOLD", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text(
            text = formatDuration(metrics.holdTimeMs),
            color = Color.White,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Body: ${metrics.bodyAngle?.let { "${it.roundToInt()}°" } ?: "--"}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = buildString {
                append(metrics.status.name.replace('_', ' '))
                metrics.trackedSide?.let { append(" · ${it.name.lowercase()} side") }
            },
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Large centred countdown during the grace period, and the final time once the session ends. */
@Composable
private fun PlankCenterOverlay(metrics: PlankMetrics, modifier: Modifier = Modifier) {
    when (metrics.status) {
        PlankStatus.GRACE_PERIOD -> Text(
            text = metrics.graceSecondsRemaining?.toString().orEmpty(),
            color = Color(0xFFFFAB40),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            modifier = modifier,
        )
        PlankStatus.FINISHED -> Column(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.large)
                .padding(horizontal = 32.dp, vertical = 20.dp)
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("FINAL TIME", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
            Text(
                text = formatDuration(metrics.holdTimeMs),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        else -> Unit
    }
}

@Composable
private fun SquatStatsPanel(metrics: SquatMetrics, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.End,
    ) {
        Text("REPS", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text(
            text = metrics.repCount.toString(),
            color = Color.White,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Good form: ${metrics.goodFormReps}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "Knee: ${metrics.liveKneeAngle?.let { "${it.roundToInt()}°" } ?: "--"}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = metrics.phase.name.replace('_', ' '),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FeedbackBanner(
    text: String,
    level: FeedbackLevel,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val background = when (level) {
        FeedbackLevel.GOOD -> Color(0xFF2E7D32)
        FeedbackLevel.WARNING -> Color(0xFFE65100)
        FeedbackLevel.INFO -> Color.Black.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier
            .background(background, MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        detail?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access is needed to track your body pose during workouts.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestPermission) { Text("Grant camera permission") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        if (onClose != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose) { Text("Back") }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
