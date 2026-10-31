package com.example.myapplication.pose

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.exercise.ExerciseAnalyzer
import com.example.myapplication.exercise.ExerciseMode
import com.example.myapplication.exercise.PlankAnalyzer
import com.example.myapplication.exercise.PlankMetrics
import com.example.myapplication.exercise.SquatAnalyzer
import com.example.myapplication.exercise.SquatMetrics
import com.example.myapplication.workout.RestState
import com.example.myapplication.workout.RestTimer
import com.example.myapplication.workout.SetGoal
import com.example.myapplication.workout.isMetBy
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Set once the current set's goal is hit; tracking is frozen from then on. */
    private val goalReached = AtomicBoolean(false)

    @Volatile
    private var setGoal: SetGoal? = null

    private val restTimer = RestTimer(viewModelScope)

    /** Rest countdown after the set goal is reached; [RestState.Idle] while tracking. */
    val restState: StateFlow<RestState> = restTimer.state

    val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        // Frozen: drop frames without running inference, saving battery during rest.
        if (goalReached.get()) imageProxy.close() else helper.detectLiveStream(imageProxy)
    }

    private val squatAnalyzer = SquatAnalyzer()
    private val plankAnalyzer = PlankAnalyzer()
    private val exerciseAnalyzers: Map<ExerciseMode, ExerciseAnalyzer> = mapOf(
        ExerciseMode.SQUAT to squatAnalyzer,
        ExerciseMode.PLANK to plankAnalyzer,
    )

    private val _exerciseMode = MutableStateFlow(ExerciseMode.SQUAT)
    val exerciseMode: StateFlow<ExerciseMode> = _exerciseMode.asStateFlow()

    /** Live rep count, knee angle and form feedback, updated on every pose result. */
    val squatMetrics: StateFlow<SquatMetrics> = squatAnalyzer.metrics

    /** Live plank hold timer, body angle, grace countdown and feedback. */
    val plankMetrics: StateFlow<PlankMetrics> = plankAnalyzer.metrics

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

    /** Switches the active exercise; the newly selected analyzer starts a fresh session. */
    fun selectExercise(mode: ExerciseMode) {
        if (mode == _exerciseMode.value) return
        exerciseAnalyzers.getValue(mode).reset()
        _exerciseMode.value = mode
    }

    fun resetExercise() = exerciseAnalyzers.getValue(_exerciseMode.value).reset()

    /**
     * Starts tracking toward [goal]. Idempotent for the same goal, so it is safe to call from
     * composition on every configuration change.
     */
    fun startSet(goal: SetGoal) {
        if (setGoal == goal) return
        setGoal = goal
        goalReached.set(false)
        restTimer.reset()
        selectExercise(goal.exercise)
        exerciseAnalyzers.getValue(goal.exercise).reset()
    }

    fun skipRest() = restTimer.skip()

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onResults(bundle: PoseResultBundle) {
        // Drop results still in flight from before the freeze so metrics stay exactly at the goal.
        if (goalReached.get()) return
        _uiState.update { it.copy(pose = bundle) }
        // Only the active exercise consumes frames, so the inactive one doesn't run its timer/state.
        exerciseAnalyzers.getValue(_exerciseMode.value).process(bundle)
        checkSetGoal()
    }

    private fun checkSetGoal() {
        val goal = setGoal ?: return
        val met = when (goal.exercise) {
            ExerciseMode.SQUAT -> goal.target.isMetBy(squatMetrics.value)
            ExerciseMode.PLANK -> goal.target.isMetBy(plankMetrics.value)
        }
        if (met && goalReached.compareAndSet(false, true)) {
            restTimer.start(goal.restSeconds)
        }
    }

    override fun onError(message: String, throwable: Throwable?) {
        reportError(message)
    }

    override fun onCleared() {
        analysisExecutor.execute { helper.close() }
        analysisExecutor.shutdown()
    }
}
