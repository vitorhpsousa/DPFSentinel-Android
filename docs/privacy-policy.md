# DPF Sentinel for Android: privacy policy

Last updated: 2026-11-11

DPF Sentinel is a read-only OBD-II logger for a diesel particulate filter. This policy covers the
Android app (`com.dpfsentinel`).

## What the app reads

**From the car.** Engine and exhaust values (soot level, pressures, temperatures, engine speed, road
speed, mileage and similar) from a Bluetooth ELM327 adapter plugged into your own car.

**From the phone, while it is logging and only if you allow the Location permission.**
- Precise location from GPS. It is used for GPS speed, altitude and road grade.
- The barometer (air pressure sensor), if the phone has one, to steady the altitude. The barometer
  needs no permission; it is only read while logging.

If you refuse the Location permission, none of the GPS values are read and logging works without
them. (On Android 11 and older, Android itself requires the Location permission before any app may
scan for a Bluetooth LE adapter, so there the app cannot find the adapter without it.)

The app reads nothing else: no contacts, accounts, advertising id, microphone, camera or files of
other apps.

## What is stored, and where

Everything is written to log files in the app's own folder on your phone, one pair of files per
drive:

- the car values above, with the time of each row;
- GPS speed, GPS altitude and its accuracy, air pressure, the combined altitude and the road grade,
  when Location is allowed;
- **your position (latitude and longitude) only if you turn on "Log position" in Settings.** It is
  off unless you turn it on. With it on, a log shows where you drove.

Your settings, including a Telegram bot token and chat id if you enter them, are stored on the phone
in the app's private settings. The app is excluded from Android's cloud backup.

## What leaves the phone

- **Nothing is sent to the developer or to anyone else.** The app has no accounts, analytics, ads,
  crash reporting or tracking, and contacts no server of the developer's.
- **Sharing.** A log file leaves the phone when you press Share on it and pick where it goes.
- **Telegram, only if you set it up.** If you enter your own Telegram bot token and chat id, the app
  sends to that chat, through Telegram's servers:
  - alerts and status replies (soot, temperatures, speed, mileage and the like);
  - a daily report that **uploads the new log files**. These are the files described above, so they
    include altitude and speed, and **your route if "Log position" is on**.

  The app also asks Telegram for messages sent to your bot, so it can answer `/status`.
  Nothing is sent to Telegram until you set this up, and it stops when you clear the token or chat id.
  What Telegram does with it is covered by Telegram's own privacy policy.

## Permissions

- Nearby devices / Bluetooth: to find and talk to the OBD adapter.
- Location (precise): GPS speed, altitude and road grade while logging, and position if you turn
  "Log position" on. Optional on Android 12 and newer; required by Android for Bluetooth scanning on
  Android 11 and older. Used only while the app is logging, which shows a notification throughout.
- Notifications: the logging notification, regeneration and high-soot alerts.
- Internet: only for Telegram, if you set it up.

## Deleting data

- Delete individual log files in the app's Logs tab.
- Uninstalling the app removes all log files and settings from the phone.
- **Files and messages already sent to Telegram stay in that Telegram chat.** The app cannot remove
  them; delete them in Telegram (delete the messages or the chat).
- To stop location being read, turn off the Location permission for the app in Android's settings.

## Contact

Questions or requests: open an issue at https://github.com/vitorhpsousa/DPFSentinel-Android/issues
