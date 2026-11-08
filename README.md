# Camera Control

An Android app that works as a wireless remote for your camera: live view, shutter,
exposure settings, and clock and GPS sync from the phone. It starts with the Nikon Z5.

## Status

- **Step 1 (done):** connect to a Nikon camera over Wi-Fi and take a photo to the memory card.
- **Step 2 (this version):** live view on the phone, a shutter button, and pickers for shutter
  speed, aperture, ISO and exposure compensation. Only the settings the current mode lets you
  set are editable (for example, in A mode shutter speed is shown but set by the camera).
  Settings changed on the camera's dials show up in the app within about a second.
  Tap the live view to focus there, or use the Focus button; Shoot focuses first.
- **Step 3 (next):** clock and GPS sync.

## Project layout

| Module   | What it is |
|----------|------------|
| `ptpip/` | The camera protocol (PTP/IP, which Nikon uses over Wi-Fi) as plain Kotlin with no Android code, so it can be tested on a computer against a simulated camera. |
| `app/`   | The Android app (Kotlin + Jetpack Compose). |

## Building and running

1. Install Android Studio and open this folder (**File > Open**). Let it sync; if it offers
   to upgrade the Android Gradle Plugin, that's fine to accept.
2. On your phone, turn on **Developer options** and **USB debugging**, plug it in, and press **Run**.

Run the protocol tests with `./gradlew :ptpip:test`.

## Connecting to the Nikon Z5

1. On the camera: **Setup menu > Connect to smart device > Wi-Fi connection > Establish Wi-Fi connection**.
   The camera shows its network name (SSID) and password.
2. On the phone, join that Wi-Fi network in Settings. Android may warn that it has no internet; choose to stay connected.
3. Open Camera Control and tap **Connect**. The camera's address is normally `192.168.1.1`.
4. The first time, the camera may ask you to confirm the connection on its screen.

If it fails, the log on screen shows each step and the camera's error code.
