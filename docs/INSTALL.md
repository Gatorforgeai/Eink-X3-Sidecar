# Installation and recovery

## Read this first

This firmware is only for the ESP32-C3 Xteink X3. Do not flash it on an X4, X4 Pro, X4C, Seeed
device, or another ESP32 e-paper board.

CrossPoint warns that unofficial firmware can permanently strand a USB-locked device after its
flashing interface is re-locked. Do not use this build on a locked or re-locked X3 unless you have an
independently tested recovery route. The safest candidate is an X3 that already appears in the
CrossPoint web flasher and can be returned to an official CrossPoint release.

Before flashing:

1. Charge the X3 and phone.
2. Confirm the X3 is detected by the official CrossPoint web flasher.
3. Confirm you can obtain the current official CrossPoint X3 firmware.
4. Save any books or files you care about.
5. Download and verify the Sidecar files before changing the device.

## Verify the release

On PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\sidecar-debug.apk
Get-FileHash -Algorithm SHA256 .\firmware.bin
```

Compare the results with `SHA256SUMS.txt` from the same GitHub release. Stop if either value differs.

## Install the Android app

The test APK is debug-signed and requires Android 14 or newer. With Android Platform Tools installed:

```text
adb install -r sidecar-debug.apk
```

The APK can also be opened on the phone and installed after allowing that file source to install
unknown apps. Android will correctly warn that this is not a Play Store build.

## Flash the X3

1. Open the official [CrossPoint flash tools](https://crosspointreader.com/#flash-tools) in a supported
   desktop browser.
2. Select X3 and the custom-binary option.
3. Choose the verified `firmware.bin` from this release.
4. Keep the cable connected until flashing and verification complete.
5. Reboot the X3. The Sidecar BLE service advertises as `EinkSidecar`.

Do not substitute a command-line flash offset unless you already understand the X3 partition table.
The hardware-tested app partition starts at `0x10000`, but blindly writing only that partition is not
a general recovery procedure.

## First run

1. Turn on Bluetooth on the phone and wake the X3.
2. Open **Eink Sidecar** and grant Nearby Devices permission.
3. Turn desired panes on and use **Customize** to set their content.
4. Set the X3 page pause. Six seconds is a comfortable starting value.
5. Press **Start Enabled Rotation**.
6. Leave Android's low-priority Sidecar delivery notification enabled so background refresh can run.

Optional permissions:

- **Notification access** enables recent-email and package-delivery summaries.
- **Calendar access** enables the calendar pane.
- Battery-optimization exemption improves scheduled delivery reliability on aggressive Android builds.

The firmware wakes for a roughly 20-second BLE check-in every two minutes while its Sidecar schedule
is armed. It continues rotating the last published pages between phone updates.

## Recovery

To leave Sidecar mode, restore the current official CrossPoint X3 firmware using the same confirmed
web-flash route. If the device no longer appears to the web flasher, stop and use the recovery process
appropriate to that exact X3 bootloader/lock state; repeated blind flashes can make recovery harder.

The Android app can be removed normally from Android settings or with:

```text
adb uninstall com.gatorforge.sidecar.trackb
```

The Sidecar firmware stores rendered collection files on the device. Restoring official firmware may
leave those files on storage; a later storage cleanup or reformat can remove them.
