# DPF Sentinel for Android: privacy policy

Last updated: 2026-11-26

DPF Sentinel is a read-only OBD-II logger for a diesel particulate filter. This policy covers the
Android app (`com.dpfsentinel`), whether installed from Google Play or from the APK on GitHub.

## In short

- **The developer collects nothing.** The app has no account, no analytics, no ads and no server of
  the developer's. Nobody but you can see your data.
- **Your data stays on your phone.** Everything the app records is stored there, for you.
- **It only leaves the phone when you send it somewhere yourself:** by sharing a log file, by
  setting up your own Telegram bot, or by connecting your own Google Drive.

The rest of this page gives the detail.

## What the app reads

**From the car.** Engine and exhaust values (soot level, pressures, temperatures, engine speed, road
speed, mileage and similar) from a Bluetooth ELM327 adapter plugged into your own car.

**From the phone's Bluetooth.**
- The name and Bluetooth address of the OBD adapter, to find it again on the next drive.
- The list of Bluetooth devices already paired with the phone, only when you tap *Choose* under
  *Car Bluetooth* in Settings, so you can pick which one is the car. Only the one you pick is kept.
- When the phone connects to a Bluetooth device, Android tells the app which device it is. The app
  compares it with the car you picked and ignores every other device.

**From the phone, while it is logging and only if you allow the Location permission.**
- Precise location from GPS. It is used for GPS speed, altitude and road grade.
- The barometer (air pressure sensor), if the phone has one, to steady the altitude. The barometer
  needs no permission; it is only read while logging.

When logging starts by itself (see below), GPS is not used until you open the app.

If you refuse the Location permission, none of the GPS values are read and logging works without
them. (On Android 11 and older, Android itself requires the Location permission before any app may
scan for a Bluetooth LE adapter, so there the app cannot find the adapter without it.)

The app reads nothing else: no contacts, accounts, advertising id, microphone, camera or files of
other apps.

## When the app runs

Logging runs as a foreground service, with a notification shown for as long as it runs. It starts:

- when you press Start, or open the app on the phone or on the Android Auto screen;
- **by itself, if you set up *Automatic logging* in Settings**: when the phone connects to the car's
  Bluetooth, when the linked OBD adapter comes into range, and at the daily time if you set one.

It stops by itself when the car is switched off or the adapter cannot be reached. After a restart of
the phone or an update of the app, the app runs briefly to set these triggers up again; that does not
start logging. To turn automatic logging off, switch off *Automatic logging* in Settings.

## What is stored, and where

Everything is written to log files in the app's own folder on your phone, one pair of files per
drive:

- the car values above, with the time of each row;
- GPS speed, GPS altitude and its accuracy, air pressure, the combined altitude and the road grade,
  when Location is allowed;
- **your position (latitude and longitude) only if you turn on "Log position" in Settings.** It is
  off unless you turn it on. With it on, a log shows where you drove.

Your settings are stored on the phone in the app's private settings. They include the adapter's and
the car's Bluetooth name and address, the last readings, alerts waiting to be sent, a Telegram
bot token and chat id if you enter them, and, if you connect Google Drive, the id of the Drive folder
and which log files have already been copied. The app does not store your Google password or your
Google account's email address. The app is excluded from Android's cloud backup.

## What leaves the phone

- **Nothing is sent to the developer or to anyone else.** The app has no accounts, analytics, ads,
  crash reporting or tracking, and contacts no server of the developer's.
- **Sharing.** A log file leaves the phone when you press Share on it and pick where it goes.
- **Telegram, only if you set it up.** If you enter your own Telegram bot token and chat id, the app
  sends to that chat, through Telegram's servers:
  - a message when logging starts, alerts and status replies (soot, temperatures, speed, mileage and the like);
  - a daily report that **uploads the new log files**. These are the files described above, so they
    include altitude and speed, and **your route if "Log position" is on**.

  The app also asks Telegram for messages sent to your bot, so it can answer `/status`.
  Nothing is sent to Telegram until you set this up, and it stops when you clear the token or chat id.
  What Telegram does with it is covered by Telegram's own privacy policy.
- **Google Drive, only if you connect it.** If you tap *Connect Google Drive* in Settings and allow
  it on Google's own screen, the app copies each finished drive's log files to a folder called
  "DPF Sentinel" in **your own** Google Drive. These are the files described above, so they include
  altitude and speed, and **your route if "Log position" is on**.
  - The app is only allowed to see the files it put there itself. It cannot see, read or change
    anything else in your Drive.
  - The files go from your phone straight to Google. The developer has no access to your Drive, your
    Google account or the files, and receives nothing.
  - Uploads wait for Wi-Fi unless you switch *Only upload on Wi-Fi* off.
  - It stops when you tap *Disconnect*. To withdraw the permission itself, remove DPF Sentinel under
    "Third-party apps and services" in your Google Account.

  What Google does with the files is covered by Google's own privacy policy. The app's use of
  information received from Google APIs adheres to the
  [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
  including the Limited Use requirements.
- **Spoken alerts** (on unless you turn them off in Settings). The alert text, such as "filter
  cleaning finished", is passed to the text-to-speech engine installed on your phone. What that engine
  does with it is up to its maker.
- **The Ko-fi button** in Settings opens a web page in your browser. The app sends nothing with it.

## Permissions

- Nearby devices / Bluetooth: to find and talk to the OBD adapter, and to notice the car's Bluetooth.
- Location (precise): GPS speed, altitude and road grade while logging, and position if you turn
  "Log position" on. Optional on Android 12 and newer; required by Android for Bluetooth scanning on
  Android 11 and older. Used only while the app is logging, which shows a notification throughout.
  The app never asks for background ("all the time") location.
- Notifications: the logging notification, regeneration and high-soot alerts.
- Foreground service (connected device, location): keeps logging running with the screen off.
- Companion device (watch for the linked adapter, start and run in the background): lets Android wake
  the app when the adapter you linked comes into range. Only used if you link the adapter.
- Run at startup: sets the automatic-logging triggers up again after a restart. It does not start logging.
- Alarms & reminders (exact alarms): the daily "look for the car" time, if you set one.
- Prevent the phone from sleeping: keeps reading the car while the screen is off.
- Internet: only for Telegram and Google Drive, if you set them up.
- View network connections: to tell whether the phone is on Wi-Fi before a Google Drive upload.

## Deleting data

- Delete individual log files in the app's Logs tab.
- Uninstalling the app removes all log files and settings from the phone.
- **Files and messages already sent to Telegram stay in that Telegram chat.** The app cannot remove
  them; delete them in Telegram (delete the messages or the chat).
- **Files already copied to Google Drive stay in your Drive.** Delete them there, in the
  "DPF Sentinel" folder.
- To stop location being read, turn off the Location permission for the app in Android's settings.

## Google Play testers

If you asked to join the Google Play test, the email address you gave is used only to add you to
the tester list in Google Play, as Google requires. It is not used for anything else or passed to
anyone else, and it is removed from the list when you ask or when the test ends.

## Contact

Questions or requests: open an issue at https://github.com/vitorhpsousa/DPFSentinel-Android/issues.
Issues are public. For anything you would rather not post in public, use the developer email shown
on the app's Google Play page.
