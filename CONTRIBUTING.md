# Contributing

This is a hobby project verified on one car (2014 Hyundai ix35 1.7 CRDi). Fixes and verified PIDs for other engines
are welcome.

## Read-only, always

The app only asks the car for readings. Do not add requests that clear codes (mode 04), write, change diagnostic
session or reprogram anything. Anything that could change ECU state does not belong in this repository.

## Build and test

```bash
./gradlew -p core test            # decoding, regen and health tests; no Android SDK needed
./gradlew :app:assembleDebug      # the APK; needs the Android SDK
```

Run the core tests before sending a change. PIDs and thresholds also live in the firmware
([DPFSentinel-ESP32-S3](https://github.com/vitorhpsousa/DPFSentinel-ESP32-S3)); a change to one should be made in
both. New PIDs need a real capture from the car: see that repo's
[CONTRIBUTING.md](https://github.com/vitorhpsousa/DPFSentinel-ESP32-S3/blob/main/CONTRIBUTING.md) and
[docs/other-cars.md](https://github.com/vitorhpsousa/DPFSentinel-ESP32-S3/blob/main/docs/other-cars.md).

## Privacy

Logs contain your odometer reading and timestamps, and can contain your position if you turned position logging on.
Redact them before sharing a log in an issue. Never commit adapter Bluetooth addresses, Telegram tokens or chat ids,
or signing keys.

## Sign off your commits

By contributing you certify the [Developer Certificate of Origin](DCO): in short, that you wrote the contribution
yourself or otherwise have the right to submit it under this project's licence (GPL-3.0-or-later). Add your
certification to every commit with `git commit -s`, which appends a line like:

```
Signed-off-by: Random J Developer <random@developer.example.org>
```
