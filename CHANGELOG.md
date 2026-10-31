# Changelog

## 1.0.1
- **Camera stick rewritten**: the point where you touch is now the neutral point, so
  touching it never turns the camera by itself; pushing in a direction turns the camera
  that way and keeps turning while you hold the push, also at the rim of the circle.
  When the game finger runs out of room it is lifted and replaced at the start.
- **Move stick lock**: drag outside the circle and release to latch walking (a lock icon
  appears); the character keeps walking until you tap the stick.
- **New button actions**: "Hold lock" (press to hold, press again to release) and
  "Auto spam" (press to start repeating, press again to stop). Each action now shows a
  short description in the control settings.
- **Repeat fix**: taps that were queued at the same spot are no longer fired together as
  two fingers, so auto-clicking keeps a steady rhythm; Shizuku taps are shorter (20 ms).

## 1.0.0 — Official release
- Sequential auto-clicker with draggable numbered click points, 40–2000 ms speed
  and an optional "stop after N clicks" limit.
- Floating menu (play/pause, add/remove point, speed, layout editor, minimize,
  close) that adapts to portrait and landscape.
- Profiles with on-screen buttons (tap / hold / toggle / spam), system buttons
  (back / home / recents) and Move / Look joysticks.
- Every control acts on its own target point; a target placed over another
  control passes the touch through to the game.
- Classic locked joystick: the white thumb stays fully inside the base.
- Built-in multi-touch engine, plus optional Shizuku mode for real, uninterrupted
  multi-touch.
- Arabic (RTL) and English, dark theme, adaptive + themed launcher icon.
- 100% offline, no Internet permission.
