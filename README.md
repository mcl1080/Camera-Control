# Camera Control

An Android app that works as a wireless remote for your camera: live view, shutter,
exposure settings, and clock and GPS sync from the phone. It starts with the Nikon Z5.

## Status

- **Step 1 (done):** connect to a Nikon camera over Wi-Fi and take a photo to the memory card.
- **Step 2 (this version):** live view on the phone, a shutter button, and pickers for shutter
  speed, aperture, ISO and exposure compensation. Only the settings the current mode lets you
  set are editable (for example, in A mode shutter speed is shown but set by the camera).
  Settings changed on the camera's dials show up in the app within about a second.
  Tap the live view to focus there, or use the Focus button. "Autofocus on shoot" can be
  switched off to keep a focus you set or to shoot in manual focus.
- After connecting, a landing page shows the camera's model, firmware, battery, mode, lens
  focal length and memory cards (shots left, free space), with the remote on its own screen.
- **Step 3 (this version):** the camera's clock is set from the phone over Wi-Fi when
  connecting (can be turned off) or with "Sync clock now", and the landing page shows whether
  the clock is in sync. Location sync pairs with the camera over Bluetooth and sends the
  phone's position (and time) every few seconds from a background service, so each photo is
  geotagged where it was taken. It works without the Wi-Fi remote connected. This is not yet
  confirmed on a Z5 (the protocol was verified on a Z7 II).

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

## Location sync (Bluetooth)

1. On the camera: **Setup menu > Connect to smart device > Pairing (Bluetooth) > Start pairing**.
   This replaces any existing SnapBridge pairing.
2. In the app's **Location** card, tap **Pair camera** and allow the location, Bluetooth and
   notification permissions. Pairing can take up to a minute; accept the Bluetooth pairing
   request if Android shows one.
3. Leave **Send location** on. A notification shows the link status while it runs. On the
   camera, check **Setup menu > Location data** shows that the phone's location is being used.

## License

Camera Control is free software under the GNU General Public License v3.0 (see `LICENSE`).
The Nikon Bluetooth pairing and location code is ported from
[nikongeotag](https://github.com/rjmendez/nikongeotag) (GPL-3.0), which builds on
nsg (AGPL-3.0) and furble (MIT).
