# MorseFinder (native Android)

Kotlin + Jetpack Compose.

* **Morse tab** - decodes Morse from sound (AudioRecord + FFT) and from blinking light (CameraX).
* **Sign language tab** - MediaPipe Hand Landmarker + a cautious nearest-neighbour classifier.
  English (ASL) and Persian labels, teach mode, import/export of sample packs.

## Ship ready-made signs in the APK
1. Teach the signs once in the app (Sign tab -> Teach).
2. "Export samples" and save the file as `app/src/main/assets/packs/base.json`.
3. Push; the APK built by GitHub Actions now recognises them without teaching.

The MediaPipe model (`hand_landmarker.task`) is downloaded during the GitHub Actions build.
