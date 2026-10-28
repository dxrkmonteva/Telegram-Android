# Tide (Telegram Android)

The "scroll to bottom" button becomes a glass of water. Its level is the number of unread messages, it sloshes
with the phone, it fills drop by drop when you open a chat, and you can pull the water out of it like a liquid
column and pour it to jump to the unread messages.

## What you see
- **Water level = unread count.** A few messages give a little water, about 50 fill the glass.
- **Fill on entering a chat.** Open a chat with unread messages and the empty glass is filled by one droplet
  per message. The counter counts up with the drops, the duration is capped at 7 s, the last drop ends with a
  surge, a light ring and a haptic confirm. Can be turned off in *Settings > Chat Settings > Tide fill animation*.
- **Tilt.** The surface follows the accelerometer: an area-conserving tilted plane, a damped wave field that
  overshoots and sloshes, a second lagging layer, meniscus at the glass wall, foam on fast waves, bubbles and a
  moving caustic.
- **Live messages.** A new message while you are in the chat drops into the glass with a small crown splash.
- **Pull to pour.** Drag the glass up (and sideways). The water stretches into a column: a chain of masses that
  lags and whips behind the finger, travelling width ripples, a pinching neck that sheds satellite drops, and a
  blob at the tip. Haptic ticks while stretching and a stronger one at the mark. Release past the mark and the column
  shatters into spray while the chat scrolls to the unread messages. Release early and the water falls back.
- A plain tap still just scrolls down.

## Rendering
- Physics runs on the CPU in `TideView` (fixed 1/120 s step, no allocations in draw).
- **OpenGL ES 3.0** (`TideGLView`, a `TextureView` with its own EGL context) draws everything in one fragment
  shader: glass, layered water, and the liquid outside the glass as a metaball field, so the column, blob and every flying
  droplet merge into one body with lit normals. If EGL, the context or the shader fail, the Canvas path in
  `TideView` takes over automatically.
- Colours come from the current theme (`key_chat_messagePanelSend`).

## Files
- `Components/chat/buttons/TideView.java`: state, physics, entry animation, Canvas fallback, snapshot for GL.
- `Components/chat/buttons/TideGLView.java`: EGL/GLES3 renderer and the shader.
- `Components/chat/buttons/ChatActivityBlurredRoundPageDownButton.java`: pull gesture (works whether or not a
  child consumed ACTION_DOWN), counter hand-off during the fill animation.
- `Components/chat/layouts/ChatActivitySideControlsButtonsLayout.java`: bigger flask (68 dp), no clipping.
- `ChatActivity.java` (side layout width), `ThemeActivity.java` + `SharedConfig.java` (the setting).

## Build and try
`.github/workflows/build-apk.yml` builds an arm64 debug APK on GitHub Actions (package `org.telegram.messenger.dxrk`,
so it installs next to the official app). Open a chat with unread messages; scroll up so the glass appears.

Based on DrKLO/Telegram (GPLv2).
