# ANRO-BEAM

**Phone to phone. Nothing in between.**

Yo! 👋 ANRO-BEAM is an Android-focused, phone-to-phone file-sharing project inspired by the simplicity of Android Beam. The dream is simple: bring two compatible phones together to start a transfer, without uploading your files to a cloud service or routing them through an ANRO server.

This is an indie project, made by a developer who likes useful, slightly weird, funny apps — and would rather talk to you like a human than sound like a corporate press release. It's also being built on a **zero-budget setup**, so there's no ANRO-operated backend or server to pay for. That budget limit shapes the architecture; it isn't a claim that servers are universally free or that a backend could never be added in the future.

> **Current build:** `v0.1.8.8`  
> **Status:** Indie project in development. Compatibility and transfer behavior can vary by device and build.

## The ANRO promise 🤝

- **0 ads. For life.** No banners, no pop-ups, no “watch an ad to continue.” That's the promise.
- **No corporate nonsense.** No pretending we're a giant company. We're here to build useful stuff and be honest about it.
- **Talk to the dev.** Got a bug, idea, question, or a very confusing error message? Open an issue and talk like a human. I prefer informal, direct conversations over corporate-speak.
- **No backend to pay for.** There's currently no ANRO-operated backend or server. The project is being built on a zero budget, and direct device-to-device sharing helps avoid server costs.
- **Your files aren't our business.** ANRO-BEAM is designed around direct device-to-device sharing, not an ANRO-operated cloud upload.
- **Be honest, even when it's awkward.** If something is broken, experimental, unsupported, or just a bad idea, we'll say so.

These are project commitments and design goals, not a claim that every feature is perfect today. No ads means no ads; it doesn't mean every bug has magically vanished. 😅

## Links

- **Website:** https://s9723651-debug.github.io/anrobeam/
- **Source code:** https://github.com/s9723651-debug/anrobeam
- **Download APK:** https://s9723651-debug.github.io/anrobeam/anrobeam.apk

Only install APKs you trust. Android may ask you to approve installation from your browser or file manager.

## A little honesty

### Why isn't ANRO-BEAM on the Google Play Store?

The Play Console developer account has a **US$25 one-time registration fee**. I haven't published ANRO-BEAM on the Play Store because I can't afford that fee right now. For now, the APK is shared directly from this project's website.

### Is ANRO-BEAM open source?

Yep — ANRO-BEAM is released under the **MIT License**. See [LICENSE](LICENSE) for the full terms. You can use, modify, and redistribute licensed project code under those terms, including in commercial projects, as long as you preserve the required copyright and license notice.

New to open source? Same journey, no gatekeeping. Advice and contributions are welcome through the repository's [Issues](https://github.com/s9723651-debug/anrobeam/issues) page.

## Project goals

- **Android-first:** built for compatible Android devices.
- **Tap to pair:** NFC is intended to initiate the connection between compatible phones.
- **Direct transfer:** Wi-Fi Direct is the intended path for transferring files after pairing.
- **No ANRO backend/server:** there isn't an ANRO-operated backend in this project right now; the developer doesn't have the budget to run one.
- **No cloud relay:** files are intended to move directly between devices, not through an ANRO-operated file server.
- **No account required:** sharing should stay simple.
- **No ads. Ever.** The ANRO promise.
- **Open development:** an independent project built in public, not a corporate product team.

These describe the project's goals and intended design; support depends on the app build, device hardware, Android version, and permissions.

## Built with & tested on 🛠️

### Development setup

- **Development laptop:** HP EliteBook 840 G6 running Windows 11 Pro.
- **Tools used:** Antigravity and ChatGPT Desktop Work to help inspect the project, review logs, edit code, and debug issues.
- **Debugging approach:** Android Debug Bridge (ADB) was used to connect test phones, reproduce problems, inspect logs, and verify fixes on real hardware.
- **Budget:** built independently with a zero-budget setup — no paid ANRO backend or server.

AI tools helped with development, but this is still an independent project. The tools don't replace real-device testing, and not every feature or Android version has been verified.

### Real-device testing

Hands-on testing and debugging have included:

- **Samsung Galaxy A16 4G** — the main modern test device.
- **Samsung Galaxy F23 5G** — used as an additional Android test device during development.

The phones were connected through ADB during debugging, and transfer issues were reproduced, investigated through logs, and retested after fixes.

This is **not** a claim that ANRO-BEAM has been tested on every Samsung model or every Android brand. Pixel, Xiaomi, OnePlus, Motorola, and other devices still need their own compatibility testing. The exact result can vary by Android version, hardware, permissions, and app build.

## Compatibility

ANRO-BEAM is intended for **compatible Android phones**. NFC hardware is required for the NFC-based pairing concept, and device support may vary.

The website includes a browser compatibility screen for non-Android user agents. This is a convenience check for the website, not a guarantee that every Android device is compatible. A browser's user-agent can also be changed or spoofed.

## Try the website demo

Open the [ANRO-BEAM website](https://s9723651-debug.github.io/anrobeam/) and select **Try the 3D demo**. The demo illustrates the intended phone-to-phone flow; it's a simulation and does not transfer a real file. No fake “live demo” claims here. 🙂

## Install and test

1. Open the [APK download link](https://s9723651-debug.github.io/anrobeam/anrobeam.apk) on a compatible Android device.
2. Install the APK if you trust the build and have chosen to allow installation from that source.
3. Grant only the permissions the app needs for its features.
4. Test with two compatible devices and non-sensitive sample files first.

Back up important files before testing development builds. If a transfer fails, note both devices, Android versions, the app build, and any error message. That info helps way more than “it doesn't work” (though we've all been there).

## Development

ANRO-BEAM is an independent project. The interface, website demo, and Android app may evolve between builds. Features shown in the website demo should not be treated as proof that the same behavior is implemented or supported on every device.

Found a bug? Have an idea? Think something is confusing? Open an [Issue](https://github.com/s9723651-debug/anrobeam/issues). You don't need fancy technical wording — explain what happened in your own words. Direct feedback from real users is the point.

## Privacy direction

The project currently has no ANRO-operated backend or server; the developer is building on a zero budget. Its design goal is direct device-to-device sharing without an ANRO cloud upload or account. “No backend” doesn't automatically prove an app is secure, so this README is not a security audit: review the source and test the exact build before using it with private or sensitive files.

## License

ANRO-BEAM is licensed under the [MIT License](LICENSE). The license applies to the project code covered by it. Third-party assets, dependencies, or other separately credited materials may have their own terms.

---

**Made independently, with zero ads promised for life, and absolutely no corporate voice required.** 🤖
