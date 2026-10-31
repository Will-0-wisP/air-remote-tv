# Security

## Threat model
Fire Air Mouse is meant for a **trusted home network**.

- Commands to the TV travel over plain UDP and are authenticated only by a short PIN. Anyone on the same network who
  learns the PIN can move the pointer and press keys. Regenerate the PIN from the TV app ("New PIN").
- ADB debugging on the stick is what enables real key events and one-tap setup. While it is on, any device you approved
  with "Always allow" can control the stick. Revoke authorisations under *Developer options → Revoke USB debugging
  authorizations* and switch ADB debugging off when not needed.
- Keys used for ADB are generated on each device and kept in the app's private storage; they are never uploaded.

## Reporting a vulnerability
Please open a private security advisory on GitHub (Security → Advisories) rather than a public issue.
