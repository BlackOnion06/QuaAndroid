package com.example.myapplication.pose

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.exercise.SquatAnalyzer
import com.example.myapplication.exercise.SquatMetrics
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class PoseUiState(
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val pose: PoseResultBundle? = null,
    val delegate: Delegate? = null,
    val errorMessage: String? = null,
)

/**
 * Owns the [PoseLandmarkerHelper] so the model survives configuration changes, and exposes
 * the latest pose to the UI. All landmarker work runs on [analysisExecutor].
 */
class PoseViewModel(application: Application) : AndroidViewModel(application),
    PoseLandmarkerHelper.Listener {

    private val _uiState = MutableStateFlow(PoseUiState())
    val uiState: StateFlow<PoseUiState> = _uiState.asStateFlow()

    /** Single thread shared by model setup, CameraX frame analysis and teardown. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val helper = PoseLandmarkerHelper(application, this).apply {
        isFrontCamera = _uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT
    }

    val analyzer = ImageAnalysis.Analyzer { imageProxy -> helper.detectLiveStream(imageProxy) }

    private val squatAnalyzer = SquatAnalyzer()

    /** Live rep count, knee angle and form feedback, updated on every pose result. */
    val squatMetrics: StateFlow<SquatMetrics> = squatAnalyzer.metrics

    init {
        analysisExecutor.execute {
            helper.setup()
            _uiState.update { it.copy(delegate = helper.activeDelegate) }
        }
    }

    fun switchCamera() {
        val newLens = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        helper.isFrontCamera = newLens == CameraSelector.LENS_FACING_FRONT
        _uiState.update { it.copy(lensFacing = newLens, pose = null) }
    }

    fun resetSquats() = squatAnalyzer.reset()

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onResults(bundle: PoseResultBundle) {
        _uiState.update { it.copy(pose = bundle) }
        squatAnalyzer.process(bundle)
    }

    override fun onError(message: String, throwable: Throwable?) {
        reportError(message)
    }

    override fun onCleared() {
        analysisExecutor.execute { helper.close() }
        analysisExecutor.shutdown()
    }
}
