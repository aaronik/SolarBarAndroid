# SolarBar for Android

A local, read-only companion to the macOS SolarBar for Android 12 and later. It reads Victron Instant Readout over Bluetooth LE; it does not pair, connect, or write to the equipment. No GX, VRM, or internet access is required.

## Sideload and set up

If you cloned this repository, [build the APK](#build-and-test) first. The generated APK is not tracked in Git.

1. Open `build/SolarBar.apk` on your Android phone. If prompted, allow the app you opened the file with (e.g. Files) to install unknown apps. Alternatively, with USB debugging enabled, run `adb install -r build/SolarBar.apk`.
2. Open **SolarBar** and tap **Allow Nearby devices** and **Allow live notification**. Turn on Bluetooth if it is off.
3. In **VictronConnect**, open each device → **Settings → ⋯ → Product info**. Enable **Instant Readout via Bluetooth**, open **Instant Readout Details → Show**, and copy the **Advertisement Key** (32 hex characters, not the Bluetooth PIN).
4. Disconnect VictronConnect if needed. In SolarBar, under **Victron devices**, tap **Add key** beside the matching device and paste its key. Use **Show key** to check the text against VictronConnect; the field also counts characters and flags non-hex input. Repeat for each device. The UI shows a short Bluetooth address suffix to disambiguate duplicate names.

The silent, ordinary notification shows up to six live readings and one 10-minute sparkline when collapsed, and up to three larger graphs when expanded. Each graph has its own scale and leaves gaps where readings are missing. Tap it for the dashboard and its 1-, 6-, or 24-hour history. Swipe the notification away to keep it hidden; **tap the SolarBar launcher icon** to re-arm it, or tap **Restore live notification** inside the app. Dismissal does not stop collecting history. No notification is shown without a fresh decoded reading, and turning Bluetooth off clears live data. Leaving range ages readings out after about 25 seconds; if still armed, the notification comes back when fresh readings return. If Android stopped the app, notification timeouts clear the old reading even if the app cannot run.

No foreground-service notification is used. Background discovery uses Android's filtered Bluetooth scan delivery and is best effort: some phones delay scanning with the screen off or under battery restrictions. Force-stopping the app, restarting the phone, or toggling Bluetooth while Android has stopped the app can require opening SolarBar again to resume. Missed readings cannot be recovered; charts show gaps. The dashboard prefers a bank-level battery monitor, totals fresh solar reports, and uses one inverter for AC output instead of double-counting. VA and W are separate. Available readings depend on the product/firmware. Keys are encrypted with Android Keystore and stored per Bluetooth device address. The app stores only the most recent 24 hours of history locally and does not export it.

## Build and test

Install a JDK (17 or newer), Android SDK Platform 35 and Build-Tools 35.0.0. Set `ANDROID_HOME` if the SDK is not at `~/Library/Android/sdk`. No Gradle or app libraries are needed.

```sh
./test.sh
./build.sh
adb install -r build/SolarBar.apk
```

The build creates a private local signing key at `~/.android/solarbar-sideload.jks`. Keep that file to install future builds over the existing app without losing settings and history. `test.sh` uses encrypted fixtures from the macOS SolarBar tests and does not require Android or Victron hardware.

### On-phone smoke test

- With Bluetooth off, open the launcher icon: the dashboard may show saved history, but the notification shade should stay clear.
- Enable Bluetooth near a configured Victron device: wait for a reading, then confirm the silent notification appears and can be expanded.
- Swipe it away and wait for several new broadcasts: it should stay hidden. Tap the launcher icon and confirm it comes back with fresh readings.
- Turn Bluetooth off with the notification visible: it should clear. With Bluetooth back on, walk out of range: the notification should clear after readings expire, and the dashboard should preserve recorded history with gaps.
- Repeat with the screen off to check how aggressively this phone restricts background Bluetooth scanning.
