# Native SDL2 controller bridge

Termux:X11-Extra captures physical and on-screen controller input in the
Android activity. The companion SDL2 backend connects to the existing
localhost UDP endpoint and publishes that state through SDL's normal joystick
and game-controller APIs.

This is deliberately below the application layer:

- native SDL2 games see a regular controller;
- Wine's SDL `winebus` backend sees the same controller after loading the
  patched native SDL2 shared library;
- multiple processes can connect at once;
- rumble is sent back to the activity;
- neither the game nor Wine needs Android `/dev/input` access.

The default endpoint is `127.0.0.1:4600`. Select `XInput` or `XDInput` under
Controller > Input backend. `Forward gamepad to X11` is independent: it enables
the floating XI2 device for non-SDL X11 clients, but the SDL bridge does not
require it.

## Protocol used by SDL2

Every datagram is 64 bytes and little-endian. SDL sends `HELLO` (`1`) followed
by `GET_GAMEPAD` (`8`, XInput request flag `1`). The activity answers with the
controller ID/name and then pumps `GAMEPAD_STATE` (`9`). SDL sends
`SET_RUMBLE` (`11`) in the reverse direction and `RELEASE_GAMEPAD` (`10`) when
the process exits.

The state payload contains a 16-bit button mask, an eight-way D-pad value, four
signed 16-bit stick axes and two unsigned 8-bit triggers. The SDL backend maps
these to six axes, one hat and twelve buttons and provides an automatic SDL
game-controller mapping.

The SDL implementation lives in the companion SDL 2.32.10 fork under
`src/joystick/termuxx11/`. Build it with
`SDL_TERMUXX11_GAMEPAD=ON`; its own
`docs/README-termux-x11-gamepad.md` contains build and runtime commands.

## XI2 fallback

The native event path now also queues the Android controller into the Lorie X
server as a floating XI2 device. It has six absolute axes and stable one-based
button numbers. Keeping it floating prevents a stick from moving the desktop
pointer while still allowing raw XI2 clients to consume it.
