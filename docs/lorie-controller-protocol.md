# LORIE-CONTROLLER X11 extension 1.0

`LORIE-CONTROLLER` is a companion to XInput2. XI2 remains the only event path
for controller axes and buttons; this extension supplies discovery metadata
and force-feedback output on the same X11 connection.

All requests use standard X11 byte order and four-byte length units. The
extension defines no events or extension-specific errors. Capability queries
for unrelated IDs return `present = 0`; output requests for invalid IDs use
normal X errors.

## Requests

Minor opcode 0, `QueryVersion`, takes 16-bit client major/minor values and
returns the negotiated 16-bit version. Version 1.0 is the current protocol.

Minor opcode 1, `QueryCapabilities`, takes a 16-bit XI2 device ID. Its 32-byte
reply includes the device ID, a present flag, axis/button/hat counts, the
standard mapping identifier, vendor/product IDs and this capability mask:

- bit 0: main rumble;
- bit 1: trigger rumble.

Minor opcode 2, `Rumble`, takes the XI2 device ID, an effect selector, two
unsigned 16-bit magnitudes and a 32-bit duration in milliseconds. Effect 0 is
main low/high-frequency rumble; effect 1 is left/right trigger rumble.

Minor opcode 3, `StopRumble`, takes the XI2 device ID and cancels active
feedback.

## Server-to-Android path

The extension validates that the supplied XI2 ID belongs to `Lorie gamepad`.
It then writes an `EVENT_GAMEPAD_RUMBLE` message to the private socket already
shared by the X server and Android `LorieView`. The Android activity dispatches
that message to `GamepadInputHandler`; no public network listener is involved.
