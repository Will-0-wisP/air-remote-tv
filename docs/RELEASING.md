# Releasing signed APKs with GitHub Actions

Debug builds from CI are signed with a throw-away key, so each build has a different signature and cannot update the
previous one. For real releases use your own key.

## 1. Create a keystore (once, keep it safe – losing it means users must reinstall)
```
keytool -genkeypair -v -keystore release.jks -alias fireairmouse -keyalg RSA -keysize 2048 -validity 10000
```

## 2. Add repository secrets
*Repository → Settings → Secrets and variables → Actions → New repository secret*

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 release.jks` (macOS: `base64 -i release.jks`) |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `fireairmouse` (or the alias you chose) |
| `KEY_PASSWORD` | the key password |

## 3. Tag a release
```
git tag v5.0.0
git push origin v5.0.0
```
The **Release** workflow builds `fire-air-mouse-phone.apk` and `fire-air-mouse-tv.apk` and attaches them to a GitHub
Release. The phone app's one-tap setup downloads `fire-air-mouse-tv.apk` from
`https://github.com/<you>/<repo>/releases/latest/download/` (the repository must be public).

## Local signed build
Create `keystore.properties` next to `settings.gradle.kts` (it is git-ignored):
```
storeFile=release.jks
storePassword=...
keyAlias=fireairmouse
keyPassword=...
```
then `gradle assemblePhoneRelease assembleTvRelease`.
