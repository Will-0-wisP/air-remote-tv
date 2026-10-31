# Changelog

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
