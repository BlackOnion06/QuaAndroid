# Third-party notices

Qua's own source code is licensed under the MIT License (see [LICENSE](LICENSE)). It bundles or
depends on the following third-party components, which remain under their own licenses.

## Bundled in this repository

| Component | File | License |
|---|---|---|
| MediaPipe Pose Landmarker (full) model, by Google | `app/src/main/assets/pose_landmarker_full.task` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Gradle Wrapper | `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |

## Dependencies (downloaded at build time, not included in this repository)

| Component | License |
|---|---|
| [MediaPipe Tasks Vision](https://github.com/google-ai-edge/mediapipe) (`com.google.mediapipe:tasks-vision`) | Apache License 2.0 |
| [AndroidX / Jetpack Compose / CameraX](https://developer.android.com/jetpack/androidx) | Apache License 2.0 |
| [Kotlin standard library and kotlinx.coroutines](https://github.com/JetBrains/kotlin) | Apache License 2.0 |

The Apache License 2.0 is compatible with distributing Qua under the MIT License, provided these
notices are kept.
