# ANRO-BEAM

**Phone to phone. Nothing in between.**

ANRO-BEAM is an Android-focused, phone-to-phone file-sharing project inspired by the simplicity of Android Beam. The goal is to make sharing feel effortless: bring two compatible phones together to initiate a transfer, without uploading files to a cloud service or routing them through an ANRO server.

> **Current build:** `v0.1.8.8`  
> **Status:** Indie project in development. Compatibility and transfer behavior can vary by device and build.

## Links

- **Website:** https://s9723651-debug.github.io/anrobeam/
- **Source code:** https://github.com/s9723651-debug/anrobeam
- **Download APK:** https://s9723651-debug.github.io/anrobeam/anrobeam.apk

Only install APKs you trust. Android may ask you to approve installation from your browser or file manager.

## A little honesty

### Why isn't ANRO-BEAM on the Google Play Store?

The Play Console developer account has a **US$25 one-time registration fee**. I haven't published ANRO-BEAM on the Play Store because I can't afford that fee right now. For now, the APK is shared directly from this project's website.

### Why isn't it open source yet?

Honestly, I haven't figured out the full process of preparing and publishing the project as open source yet. I want to learn how to do it properly rather than pretend it's already done. Until a license is added and the project is clearly published under it, please don't assume the code is open source or freely reusable.

**Want to help?** If you know how to help me publish ANRO-BEAM as an open-source project, email me at [s9723651@gmail.com](mailto:s9723651@gmail.com). Advice and guidance are welcome.

## Project goals

- **Android-first:** designed for Android devices.
- **Tap to pair:** NFC is intended to initiate the connection between compatible phones.
- **Direct transfer:** Wi-Fi Direct is the intended path for transferring files after pairing.
- **No cloud relay:** files are intended to move directly between devices, not through an ANRO-operated file server.
- **No account required:** the goal is to keep sharing simple.
- **Open development:** built as an independent project.

These describe the project's goals and intended design; support depends on the particular app build, device hardware, Android version, and permissions.

## Compatibility

ANRO-BEAM is intended for **compatible Android phones**. NFC hardware is required for the NFC-based pairing concept, and device support may vary.

The website includes a browser compatibility screen for non-Android user agents. This is a convenience check for the website, not a guarantee that every Android device is compatible. A browser's user-agent can also be changed or spoofed.

## Try the website demo

Open the [ANRO-BEAM website](https://s9723651-debug.github.io/anrobeam/) and select **Try the 3D demo**. The demo illustrates the intended phone-to-phone flow; it is a simulation and does not transfer a real file.

## Install and test

1. Open the [APK download link](https://s9723651-debug.github.io/anrobeam/anrobeam.apk) on a compatible Android device.
2. Install the APK if you trust the build and have chosen to allow installation from that source.
3. Grant only the permissions the app needs for its features.
4. Test with two compatible devices and non-sensitive sample files first.

Back up important files before testing development builds. If a transfer fails, note both devices, Android versions, the app build, and any error message.

## Development

ANRO-BEAM is an independent project. Its interface, website demo, and Android app may evolve between builds. Features shown in the website demo should not be treated as proof that the same behavior is implemented or supported on every device.

Bug reports and suggestions are welcome through the repository's [Issues](https://github.com/s9723651-debug/anrobeam/issues) page.

## Privacy direction

The project's design goal is direct device-to-device sharing without an ANRO cloud upload or account. This README is not a security audit: review the source and test the exact build before using it with private or sensitive files.

## License

A license file has not yet been added to this repository. Until a license is published, do not assume the source is available for unrestricted reuse or redistribution.
