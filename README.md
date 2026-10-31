# Qua

[![License: MIT](https://img.shields.io/badge/License-MIT-C6FF00.svg)](LICENSE)

A free, open-source AI personal trainer for Android. Qua uses your phone's camera to track your
body pose in real time and coaches your exercise form, entirely on-device. No subscription, no
extra hardware, and your video never leaves your phone.

## Features

- Real-time pose tracking with Google MediaPipe Pose Landmarker (33 body landmarks, 3D world
  coordinates), GPU-accelerated with automatic CPU fallback
- **Squats:** automatic rep counting, depth check ("Incomplete depth") and knee-cave detection
  ("Push knees out"), with good-form reps tracked separately
- **Plank:** hold timer with body-alignment angle tracking
- Sets with goals and rest timers

## Tech stack

Kotlin · Jetpack Compose · CameraX · MediaPipe Tasks Vision · minSdk 24 (Android 7.0+)

## Build and run

1. Open the project in Android Studio.
2. Connect a physical Android device (the camera is required).
3. Run the `app` configuration.

## License

Qua is released under the [MIT License](LICENSE): you are free to use, copy, modify and
distribute it, including commercially, as long as the license notice is kept.

Third-party components (including the bundled MediaPipe pose model, Apache License 2.0) are listed
in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
