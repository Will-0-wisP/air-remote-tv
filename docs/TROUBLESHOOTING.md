# Troubleshooting

Tap the **status line** in the phone app for a details popup (phone → TV link and TV app link). Typical messages:

| Message | Meaning / fix |
|---|---|
| `ADB debugging is OFF (or unreachable)` | Fire TV: *Settings → My Fire TV → Developer options → ADB debugging = ON*. Check the IP and that both devices share the Wi-Fi (no AP/client isolation). |
| `WAITING: on the TV choose “Always allow”…` | Use the original remote or the Fire TV phone app to accept **Allow USB debugging?** (tick *Always allow*). |
| `The TV closed the approval prompt` | The prompt was denied or timed out. Tap **Retry** and accept it. |
| `Failed at “sent CNXN”: EOFException` (TV app) | Expected on some sticks: they refuse ADB connections from apps on the stick itself. The phone link is used instead. |
| `Failed at “shell (reused link)”` | The old link went stale (Wi-Fi sleep). The app reconnects by itself; tap **Retry** if it persists. |
| `Wrong PIN` | Run **One-tap setup** again (it sets a fresh PIN) or type the PIN shown on the TV app. |
| `Limited: …` | Only the fallback is active (no ADB link). Run **One-tap setup** or **Enable real remote keys**. |
| Netflix / YouTube / Prime: pointer moves but nothing is highlighted | These apps draw their own screens and give Android nothing to read, so the item under the pointer cannot be known. With **Settings → Arrow steps** on, moving the phone while holding the pad presses arrow keys instead (step size adjustable). The details popup shows `mode=arrow-steps`. |
| Pointer appears but nothing is highlighted | The screen does not expose focusable items to accessibility (e.g. video). Use the D-pad/swipe pad there. |
| Play Protect warning on the phone | Normal for sideloaded apps. Tap *More details → Install anyway*. Signing releases with your own key helps. |
| TV app says `Service: OFF` or “Pointer permission is missing” | Phone app → **Connect → 🔓 Allow pointer on TV**, then open the TV app and select **Enable pointer** (it also switches on by itself once the permission exists). **One-tap setup** does this as well. |

## Resetting
- Phone: clear the app's data to forget the IP, PIN and ADB key.
- Stick: *Developer options → Revoke USB debugging authorizations*, or uninstall the TV app.
