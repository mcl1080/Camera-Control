# Latlo

An Android app that keeps your camera's location and clock in sync with your phone. It pairs
with the camera over Bluetooth and sends the phone's GPS position and time in the background, so
every photo is geotagged and timestamped correctly. It works with Nikon, Sony, Canon, Fujifilm
and Ricoh cameras that pair with their maker's phone app.

## Project layout

| Module      | What it is |
|-------------|------------|
| `protocol/` | Each brand's Bluetooth pairing and location/time messages as plain Kotlin with no Android code, so they can be tested on a computer. |
| `app/`      | The Android app (Kotlin + Jetpack Compose): pairing, the background sync service and the screen. |

## Trying a test build

Download the latest APK from [Releases](https://github.com/mcl1080/Camera-Control/releases) and
open it on your phone (Android 10 or newer) to install it; the release page has step-by-step
instructions. If you try it with a camera, please
[send a test report](https://github.com/mcl1080/Camera-Control/issues/new?template=camera-test-report.yml),
especially for Sony, Canon, Fujifilm and Ricoh, which haven't been tried yet.
How releases are made is in [RELEASING.md](RELEASING.md).

## Building and running

1. Install Android Studio and open this folder (**File > Open**). Let it sync; if it offers
   to upgrade the Android Gradle Plugin, that's fine to accept.
2. On your phone, turn on **Developer options** and **USB debugging**, plug it in, and press **Run**.

Run the protocol tests with `./gradlew :protocol:test`.

## Location sync (Bluetooth)

Location and time sync works with Nikon, Sony, Canon, Fujifilm and Ricoh cameras that pair with
their maker's phone app over Bluetooth. Only Nikon has been tried on a real camera so far; the
other brands use protocols reverse-engineered by open-source projects (see License) and are
untested here.

| Brand | Pairing mode on the camera (menu names vary by model) | What gets sent |
|-------|------------------------------------------------------|----------------|
| Nikon Z | Setup > Connect to smart device > Pairing (Bluetooth) > Start pairing | Location every few seconds; clock on connect |
| Sony Alpha / ZV | Network > Bluetooth: Bluetooth Function on, then Pairing; turn on Location Info. Link | Location every few seconds; clock on connect |
| Canon EOS / PowerShot | Wireless settings > Connect to smartphone > Add a device. Accept the phone on the camera's screen | Location (with time) when the camera asks |
| Fujifilm X / GFX | Network/USB settings > Bluetooth/Smartphone setting > Pairing registration | Location (with time) when the camera asks, about every 10 s |
| Ricoh GR III / IIIx / IV | Settings > Bluetooth > Pairing | Location every few seconds; clock on connect |

1. Put the camera in Bluetooth pairing mode as above. This replaces any pairing with the maker's
   app (SnapBridge, Creators' App, Camera Connect, XApp or GR World).
2. Open Latlo, tap **Pair camera** and allow the location, Bluetooth and
   notification permissions. Pairing can take up to a minute; accept the Bluetooth pairing
   request if Android shows one.
3. Leave **Send location** on. A notification shows the link status while it runs. On a Nikon,
   check **Setup menu > Location data** shows that the phone's location is being used.

Once it's on, there's nothing more to do in the app: it keeps running in the background (with a
notification) and starts sending the clock and location whenever the camera turns on. GPS only
runs while the camera is connected, to save battery. To have it start again by itself after the
phone restarts, tap **Allow all the time** in the Background card and choose that option for location.

## License

Latlo is free software under the GNU General Public License v3.0 (see `LICENSE`).
The Nikon Bluetooth pairing and location code is ported from
[nikongeotag](https://github.com/rjmendez/nikongeotag) (GPL-3.0), which builds on
nsg (AGPL-3.0) and furble (MIT). The Sony and Ricoh protocols come from
[CameraSync](https://github.com/rock3r/CameraSync) (Apache-2.0), and the Canon and Fujifilm
protocols from [furble](https://github.com/gkoh/furble) (MIT).
