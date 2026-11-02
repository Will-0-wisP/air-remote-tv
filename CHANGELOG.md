# Changelog

## 5.3
- TV: pointer highlight/selection now works inside apps – scans the top application window, accepts nodes that only expose a click/focus action, no longer discards whole subtrees that report "not visible", and handles apps with nothing focused yet. The phone's details popup shows the last scan (package, node and item counts).

## 5.2.1
- CI: removed the setup-android action (it failed with "Failed to find package 'tools'"); the runner's preinstalled SDK is used and licenses are accepted in a plain step.
- CI: workflow actions updated to Node 24 versions (checkout v6, setup-java v5, setup-android v4, setup-gradle v6, upload-artifact v6).

## 5.2
- Fix: buttons in the phone app's Connect dialog had zero width and were invisible (Find TV, One-tap setup, Allow pointer on TV, Install from file, Enable real keys). The dialog now scrolls, shows every action in order and displays the app version.

## 5.1
- Phone: **Allow pointer on TV** – runs `adb connect`, `pm grant … WRITE_SECURE_SETTINGS` and a package check in one tap (no computer).
- TV app: buttons are no longer numbered; clear message when the permission is missing; switches the pointer service on by itself when the permission is present.

## 5.0
- One-tap setup from the phone: install the TV app (download from the latest release or pick a file), enable the pointer
  service, pair a PIN – no computer needed.
- Built-in ADB client can capture command output and install APKs.
- GitHub repository files: CI builds, release workflow, docs.

## 4.x
- Real remote keys through ADB (phone-direct), automatic reconnect and keep-alive.
- One swipe = one arrow press; pointer focus-follow with key-based correction.
- Fire-remote-style phone UI with D-pad / Swipe / Air mouse tabs.

## 1.x–3.x
- Bluetooth HID prototype, then Wi-Fi pointer service with accessibility overlay.
