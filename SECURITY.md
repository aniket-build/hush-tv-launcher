# Security & privacy

Hush is a home screen for TVs that often stopped getting security updates years ago. It's designed so that installing it **adds as little risk as possible**. This page explains exactly what it can and can't do.

## What Hush can do
| Capability | Why | Limits |
|---|---|---|
| List installed TV apps and open them | It's a home screen | Uses Android's standard app list; nothing is sent anywhere |
| **Usage access** (optional) | "Recently played" counts apps opened from remote buttons too | Read only inside Hush, held in memory, never stored or shared |
| **Accessibility service** "Hush – Home button" | Android TV always gives the Home button to the stock launcher, so Hush catches the Home key and opens itself | Acts **only** on the Home key; every other key passes straight through, unread and unstored. Window events are limited to the stock launcher. **Cannot read anything on screen** (`canRetrieveWindowContent=false`) |

## What Hush cannot do
- **No internet access.** Hush doesn't request the `INTERNET` permission, so Android itself blocks it from sending or receiving anything. No ads, analytics, crash reporting or update checks.
- **No storage access.** It doesn't read your files or shared storage. (Earlier private builds could load a wallpaper photo from shared storage. That was removed, because on older Android any app could plant a malicious image there.)
- **No backups.** `allowBackup=false`: your recently played list never leaves the TV.
- **No third-party code.** Plain Java against the Android framework, with no libraries or SDKs.
- **Nothing is disabled on your TV.** The stock launcher stays installed and enabled as the fallback.

## Defence in depth
- The Home-button helper runs in its **own process** (`:redirect`), separate from the screen that draws other apps' banner images.
- **Crash-loop guard:** if Hush keeps failing to open, the helper backs off and leaves the stock home screen in place.
- **Escape hatch:** press Home twice quickly to reach the stock home screen at any time.
- Only the home-screen activity is reachable by other apps. The More Apps screen and the helper service are not exported.

## Verify your download
Only install `hush.apk` from this repository's **Releases** page.

1. **Checksum:** compare the file's SHA-256 with the one in the Release notes:
   ```bash
   shasum -a 256 hush.apk        # macOS / Linux
   certutil -hashfile hush.apk SHA256   # Windows
   ```
   `scripts/enable-as-home.sh` shows the checksum and asks you to confirm before installing.
2. **Signing certificate** (the same for every official release):
   ```
   CN=Hush TV Launcher
   SHA-256: 52:90:b7:e7:0b:9f:b8:c1:f3:2e:10:9d:7b:18:9d:97:c7:ac:87:6b:7b:f2:20:91:b0:37:87:a1:6c:eb:5b:fe
   ```
   Check with `apksigner verify --print-certs hush.apk` (Android SDK build-tools). After the first install, Android itself refuses any "update" that isn't signed with this same key.

## Your TV's security, beyond Hush
The biggest risk in setting Hush up is **ADB debugging**, which lets computers on your Wi-Fi ask to control the TV. **Turn it off when you're done** (the install script offers to do it for you). Never approve the "Allow USB debugging?" prompt for a computer you don't recognise.

## Reporting a vulnerability
Please **don't open a public issue**. Use GitHub's **Security → Report a vulnerability** (private advisory) on this repository. I'll reply within a week.
