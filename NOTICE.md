# Notices

DPF Sentinel for Android
Copyright (C) 2026 Vitor Sousa and the DPF Sentinel contributors

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version (GPL-3.0-or-later).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License in
[LICENSE](LICENSE) for more details.

## Origin

This app was split out of [DPFSentinel-ESP32-S3](https://github.com/vitorhpsousa/DPFSentinel-ESP32-S3) (the board
firmware), with its git history. `core/` is a Kotlin port of that firmware's logic.

## Third-party software

No third-party source code is included in this repository. The build downloads these libraries through Gradle, each
under the Apache License 2.0, and they are packaged into the APK:

- AndroidX Core, Activity, Lifecycle, Compose (UI, Foundation, Material 3) and Car App Library: The Android Open
  Source Project
- Kotlin standard library and kotlinx.coroutines: JetBrains s.r.o. and contributors

The optional Google Drive back-up also uses **Google Play services (`play-services-auth`)**, which is not open
source: it is Google's library, under the [Android Software Development Kit License](https://developer.android.com/studio/terms).

### Additional permission under GNU GPL version 3 section 7

If you modify this program, or any covered work, by linking or combining it with Google Play services (or a modified
version of that library), containing parts covered by the terms of the Android Software Development Kit License, the
licensors of this program grant you additional permission to convey the resulting work.

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are the unmodified Gradle Wrapper, Apache License 2.0.

[DCO](DCO) is the Developer Certificate of Origin 1.1, copyright The Linux Foundation and its contributors.

## Names

Hyundai, ix35, Android, Android Auto, Google Play, Google Drive and Telegram are trademarks of their owners. This project is not
affiliated with or endorsed by any of them.
