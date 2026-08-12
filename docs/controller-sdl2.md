# Native SDL2 controller bridge

Termux:X11-Extra captures physical and on-screen controller input in the
Android activity and exposes it as a floating XInput2 device. The companion
SDL2 backend publishes that XI2 device through SDL's normal joystick and
game-controller APIs.

This is deliberately below the application layer:

- native SDL2 games see a regular controller;
- Wine's SDL `winebus` backend sees the same controller after loading the
  patched native SDL2 shared library;
- multiple X11 clients can consume it at once;
- rumble is sent through the `LORIE-CONTROLLER` X11 extension;
- neither the game nor Wine needs Android `/dev/input` access.

Enable `Forward gamepad to X11` in the Android app. No SDL-specific host, port
or controller ID is required: both input and feedback use the X connection
selected by `DISPLAY`, which is normally a Unix-domain socket on Termux:X11.

## X11 protocols used by SDL2

Axes, buttons and the D-pad are ordinary XI2 events. The device has six
absolute axes and sixteen stable one-based X buttons; buttons 11 through 14
form the D-pad hat. SDL exposes the resulting standard layout as six axes, one
hat and twelve buttons.

XI2 has no output protocol. The companion `LORIE-CONTROLLER` 1.0 extension
therefore provides `QueryVersion`, `QueryCapabilities`, `Rumble` and
`StopRumble` requests. Requests identify the same controller by its XI2 device
ID. The server forwards feedback over its existing private UI channel to the
Android controller vibrator; SDL opens no additional UDP or Unix socket.

The SDL implementation lives in the companion SDL 2.32.10 fork under
`src/joystick/termuxx11/`. Build it with
`SDL_TERMUXX11_GAMEPAD=ON`; its own
`docs/README-termux-x11-gamepad.md` contains build and runtime commands.

## Legacy compatibility

The Android fork still contains its older UDP bridge for clients that depend on
that protocol. The new SDL backend neither opens nor uses it. On an older
Termux:X11 build without `LORIE-CONTROLLER`, SDL input continues to work through
XI2 while rumble capability is correctly reported as unavailable.
