# Building from source

## Android

Requirements: JDK 17 and an Android SDK containing API 36.

```text
cd android
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

On Windows, use `gradlew.bat`. The app APK is written under
`android/app/build/outputs/apk/debug/`.

## X3 firmware

The public snapshot avoids exposing the private development repositories. It provides two patches
against public, immutable upstream commits.

```text
git clone https://github.com/crosspoint-reader/crosspoint-reader.git
cd crosspoint-reader
git checkout eea75d561b8cd4fe4a9ef3850488a4561af0b8e6
git submodule update --init --recursive

cd freeink-sdk
git checkout ae68356de994a536dd6c45615a42ea485b3dddca
git apply /path/to/firmware/freeink-sdk/freeink-sidecar.patch
cd ..

git apply /path/to/firmware/crosspoint-sidecar.patch
pio run -e default
```

The resulting binary is normally under `.pio/build/default/firmware.bin`. Compare a rebuilt binary's
behavior and protocol output with the release evidence; a byte-identical result can depend on the
toolchain versions resolved by PlatformIO.

The corresponding changed files are also available under `firmware/source-delta/` and
`firmware/freeink-sdk/source-delta/` for direct review.
