# Architecture

## Two apps, one module
`app/` builds two product flavours:

| Flavour | Application id | Runs on | Role |
|---|---|---|---|
| `phone` | `app.fireairmouse.remote` | Android phone | UI, gyro, UDP client, ADB client, one-tap setup |
| `tv` | `com.example.fireairmouse.tv` | Fire TV | setup screen, accessibility service (pointer + focus), UDP server |

Shared code (`src/main`): `AdbRemote` (ADB client) and `RemoteKeys` (key codes).

## UDP protocol (port 9876)
Every message is text: `PIN|COMMAND|ARG`. The TV ignores messages with a wrong PIN (it answers `NOPIN` to `PING`).
Broadcast `HELLO` (no PIN) is answered with `HELLO_R|<model>` for discovery.

| Command | Arg | Meaning |
|---|---|---|
| `PING` | – | answered with `PONG|<model>|ADB or LIMITED|<tv adb status>` |
| `M` | `dx,dy` | move pointer (relative) |
| `AIM` | `1` / `0` | pad held / released (released → focus settles on the item or the nearest one) |
| `LK` | `1` / `0` | pointer locked (heartbeat while locked) |
| `K` | key name | remote key (`UP`, `OK`, `BACK`, `HOME`, `MENU`, `PLAY`, …) – used when the phone has no direct ADB link |
| `T` / `B` | text / – | type text / backspace |
| `ADB` | – | ask the TV app to (re)connect its own ADB link |

The TV can also send `KEY|<keycode>` back to the phone, asking it to press a key through the phone's ADB link
(used when the stick refuses ADB connections from its own apps).

## ADB client (`AdbRemote`)
A small implementation of the ADB wire protocol over TCP port 5555: `CNXN` → `AUTH` (signature, then public key for the
on-screen approval) → `OPEN shell:` / `exec:`. Notes:
- Handshake variants (`v1/v2/v3`) and hosts are tried in order; once the approval prompt has been shown it never retries.
- Key presses are queued and sent as one `input keyevent a b c` command when several arrive together.
- An old link that goes stale is replaced and the command repeated once; an idle link is kept alive.
- `installApk` streams an APK through `exec:cmd package install -S <size> -r -t`.

## Pointer and focus (TV)
The accessibility service scans the active window for the leaf-most interactive nodes (focusable / clickable). While the
pad is held, the item under the pointer receives focus (`ACTION_FOCUS` on it or its nearest focusable parent). On release
the focus is verified and, if needed, walked to the item with real D-pad key presses until it lands there. When the remote
keys move focus while the pointer is visible, the pointer follows.

## One-tap setup (phone)
1. connect over ADB (the TV shows the approval prompt once),
2. if the TV app is missing: download it from the latest release URL (`BuildConfig.TV_APK_URL`) or use a picked file,
   then `cmd package install`,
3. `pm grant … WRITE_SECURE_SETTINGS`, then add the accessibility service to `enabled_accessibility_services`,
4. `am start … --es pin <new PIN>` and wait for the TV app to answer.
