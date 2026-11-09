**This is a test build.** Latlo is a wireless remote and clock/GPS sync app for cameras. It has been used on a Nikon Z5; everything else is untested, and that's what we'd love your help with.

## What to test

| Feature | Cameras | Status |
|---|---|---|
| Wi-Fi remote (live view, shutter, exposure settings, clock sync) | Nikon Z | Tested on a Z5 |
| Bluetooth location and time sync | Nikon Z | Tested on a Z5 |
| Bluetooth location and time sync | Sony Alpha / ZV, Canon EOS / PowerShot, Fujifilm X / GFX, Ricoh GR III / IIIx / IV | **Untested, please try it** |

Pairing steps for each brand are in the [README](https://github.com/mcl1080/Camera-Control#location-sync-bluetooth). Pairing Latlo replaces your camera's pairing with the maker's app, so you'll need to pair that app again afterwards if you want to go back.

## Installing (side-loading)

You need Android 10 or newer.

1. On your phone, download **Latlo-…apk** from the Assets list below.
2. Open it from your notifications or the Files / Downloads app.
3. If Android asks, allow your browser or Files app to **install unknown apps**, then go back and tap **Install**.
4. If Play Protect warns that it doesn't recognise the app, tap **More details > Install anyway**. That's expected for apps that don't come from the Play Store.

Later test builds install over this one and keep your settings, as long as you got both from this page.

To check the download, compare its SHA-256 with `SHA256SUMS.txt`.

## Reporting results

Whether it works or not, please [open a camera test report](https://github.com/mcl1080/Camera-Control/issues/new?template=camera-test-report.yml) with your camera model and firmware, phone model and Android version, and what happened. A screenshot of the app's log helps a lot when something fails.
