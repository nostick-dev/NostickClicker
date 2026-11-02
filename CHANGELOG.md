# Changelog

## 1.0.3
- **Control look menu**: tap a control in the layout editor to change how it LOOKS: shape
  (rounded / circle), colour (10 presets), opacity (10–100 %, so it can be see-through like the
  sticks), size and label, with a live preview. Sticks get opacity and size.
- **Click settings menu**: tap the green marker to change what the control DOES: action (tap,
  hold, spam, hold lock, auto spam, back / home / recents), repeat speed and stick sensitivity.
- **Targets under other controls**: a green target can sit on top of any control (for example
  a prone button under the move stick) without pressing it. Each visible control is only a
  picture; the touches are caught by invisible windows that leave a small hole around every
  target point, so the tap reaches the game. Nothing is switched off or cancelled while you
  hold a control, with or without Shizuku. (The area right around a target, about 44 dp, is not
  pressable on the control underneath.)
- **Look pad edge turning**: inside the pad the camera copies your thumb 1:1. At the pad's edge
  it keeps turning by itself (faster the further you push); moving back inwards stops it at once
  and 1:1 control resumes without a jump.
- **Look pad stutter**: the game finger is lifted only when it reaches the screen edge and is put
  back as far behind as the screen allows. Shizuku moves are merged into one steady stream that
  keeps the timing of your real touch.
- **Move stick lock survives your own touches**: after Android interrupts the held finger it is
  pressed again at the stick's base and slid back, so walking keeps its direction.

## 1.0.2
- **Look pad**: the camera control is now a touch pad that behaves like touching the game
  screen directly. The game finger copies the movement of your thumb 1:1 (scaled by the
  sensitivity), with no joystick drift, no dead zone and no speed ramp. Place it anywhere,
  up to 416 x 320 dp. When the game finger runs out of room it is lifted and put back
  behind the start point, so the camera never stops turning.

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
