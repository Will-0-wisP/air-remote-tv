# Fire Air Mouse

Use your Android phone as a **gyroscope air mouse and full remote** for an Amazon Fire TV Stick — with the same
D-pad / Back / Home / Menu / media / volume behaviour as the original remote, a swipe pad, and a pointer that
moves the app's own focus highlight. Setup is done **from the phone**; no computer is needed.

> **Disclaimer:** unofficial hobby project. Not affiliated with, endorsed by, or sponsored by Amazon.
> "Fire TV" and "Fire Stick" are trademarks of Amazon.com, Inc. Use at your own risk.

## Features

- **D-pad tab** – round ring with OK in the centre (hold an arrow to repeat).
- **Swipe tab** – one swipe = one arrow press in the swipe direction.
- **Air mouse tab** – hold the pad and move the phone; the pointer appears only while holding (or when locked) and
  hides 3 s later. The app's own focus highlight follows the pointer and stays where you let go.
  **Lock pointer** freezes the pointer; swipes then move the selection.
- **Real remote keys** – Back, Home, Menu, rewind/play/fast-forward, volume and D-pad are sent as genuine key events
  through the stick's ADB debugging, so they behave exactly like the Fire remote.
- **Keyboard** – type on the phone, text goes into the focused field on the TV.
- **One-tap setup** – installs the TV app, switches the pointer service on and pairs a PIN, all over ADB from the phone.

## How it works (short)

```
 Phone app ──ADB over Wi-Fi (port 5555)──► Fire TV  : real key events (input keyevent), install, setup
     │
     └────UDP (port 9876, PIN protected)──► TV app  : pointer position, focus-follow, text, status
```

The **TV app** runs an Android *accessibility service* that draws the pointer, finds the item under it and moves
focus. It never stores or transmits what is on screen. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

- Fire TV Stick with **Fire OS 6 or newer** (Android 7.0+). Developed on a Fire TV Stick Lite (AFTSS).
- Android phone, Android 7.0+, with a gyroscope.
- Phone and Fire TV on the **same Wi-Fi** (router "AP/client isolation" must be off).

## Quick start (no PC)

1. **Get the APKs** – from the [Releases](../../releases) page (`fire-air-mouse-phone.apk`, `fire-air-mouse-tv.apk`),
   or build them yourself (below). Install the **phone** APK on your phone.
2. **On the Fire TV** (needs your original remote or the Amazon Fire TV phone app, once):
   *Settings → My Fire TV → About* → click the device name 7 times, then
   *Settings → My Fire TV → Developer options → ADB debugging = ON*. Note the IP under *About → Network*.
3. **On the phone:** open **Fire Air Mouse → Connect**, type the TV's IP address, tap **⚡ One-tap setup**.
   When the TV shows **“Allow USB debugging?”** choose **Always allow** → OK.
   *(Only the pointer permission is missing? Use **🔓 Allow pointer on TV** instead – it runs `adb connect`, `pm grant …
   WRITE_SECURE_SETTINGS` and a package check for you, then select **Enable pointer** on the TV.)*
4. The app downloads the TV app from this repository's latest release (or asks you to pick the TV APK file),
   installs it on the stick, turns the pointer service on and pairs. The status line turns green:
   **Connected … real keys ✔**.

If something fails, tap the status line for details and see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Build from source

**Android Studio:** open the folder, choose *Build → Select Build Variant* (`phoneDebug` / `tvDebug`), build.

**Command line** (Gradle 8.7+, JDK 17, Android SDK 34):

```
gradle assemblePhoneDebug assembleTvDebug
# APKs: app/build/outputs/apk/{phone,tv}/debug/
```

**GitHub Actions:** every push builds debug APKs (see the *Actions* tab → artifacts). Tagging `vX.Y.Z` builds **signed**
release APKs and publishes them — see [docs/RELEASING.md](docs/RELEASING.md). In Actions the phone app is automatically
pointed at `…/releases/latest/download/fire-air-mouse-tv.apk` of *your* repository (the repository must be public for the
in-app download; otherwise the app falls back to a file picker).

## Project layout

```
app/src/main    shared code: ADB client (AdbRemote), key map
app/src/phone   phone app: UI, UDP client, one-tap setup
app/src/tv      TV app: setup screen, accessibility service, pointer overlay
docs/           architecture, troubleshooting, releasing
.github/        CI workflows
```

Two Gradle product flavours share one module: `phone` (`app.fireairmouse.remote`) and `tv`
(`com.example.fireairmouse.tv`).

## Security & privacy

- The UDP link is **not encrypted**; it is protected only by a 4-digit PIN. Use it on a network you trust.
- The phone and TV apps each create their own RSA key for ADB and store it in app-private storage.
- ADB debugging lets an authorised device control the stick. Turn it off when you do not need it
  (the app then falls back to a limited mode). See [SECURITY.md](SECURITY.md).
- The accessibility service reads the on-screen element structure locally to place focus. Nothing is stored or sent.

## Known limitations

- Pointer-to-focus only works on screens that expose their items to Android accessibility (most menus and tiles; not
  video surfaces or some custom-drawn screens).
- The Alexa/voice button, power, and the stick's own Menu long-press menus cannot be reproduced.
- Each key press runs `input keyevent` on the stick, which takes a few hundred milliseconds on low-end sticks.
- Google Play Protect may warn about sideloaded apps from unknown developers; sign releases with your own key.

## Contributing

Issues and pull requests are welcome. Please include your Fire OS version and the "details" text shown when you tap
the status line in the phone app.

## License

[MIT](LICENSE)
