# NostickClicker 1.0.1

Native Android (Kotlin) auto-clicker and game-controls overlay. 100% offline.
Created by **Nostick**. © 2026 Nostick — all rights reserved.

> ⚠️ **Proprietary.** You may not copy, modify, distribute or sell this project
> without written permission from Nostick, and the owner must always be credited.
> Read `LICENSE` before doing anything with these files.

## Features
- Auto-clicker: numbered click points, 40–2000 ms, stop-after limit.
- Floating menu (portrait/landscape), minimize to a bubble.
- Profiles: buttons (tap / hold / hold lock / spam / auto spam), system buttons, Move / Look joysticks.
- Move stick with walk lock; Camera stick that turns continuously and starts from where you touch.
- Each control taps its own target point (green numbered marker).
- Optional Shizuku mode for real multi-touch without stutter.
- Arabic / English, dark UI, no Internet permission.

## Requirements
Android 8.0+ (API 26). Accessibility service + "Display over other apps".

## Build
Android Studio (SDK 36, JDK 17) or the GitHub Actions workflow
`.github/workflows/build.yml` (push a tag like `v1.0.0` to publish a release).
Signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Notes
- Taps are injected through `AccessibilityService.dispatchGesture()`. Android allows
  one gesture at a time, so the app merges all fingers into one gesture. For fully
  smooth multi-touch, enable the optional Shizuku mode.
- Android does not let accessibility services inject keyboard keys, so keys are
  not supported; system buttons (Back / Home / Recents) are provided instead.
- Some games forbid automation. You are responsible for following their rules.

## Documents
`LICENSE` · `PRIVACY_POLICY.md` · `THIRD_PARTY_NOTICES.md` · `CHANGELOG.md`
