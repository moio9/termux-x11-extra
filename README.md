# Termux:X11-Extra

Termux:X11-Extra is an X11 server for running graphical Linux applications from Termux on Android. It adds customizable on-screen controls and controller options to Termux:X11.

## Install

You need Android 7 or newer and a working Termux installation. Install both the Android app and its matching Termux companion package:

1. Download and install [Termux:X11-Extra for Android](https://github.com/moio9/termux-x11-extra/releases/download/v2.0-alpha.2/termux-x11-universal-debug.apk).
2. Open Termux and run:

   ```sh
   pkg install x11-repo wget
   wget -O termux-x11-extra.deb https://github.com/moio9/termux-x11-extra/releases/download/v2.0-alpha.2/termux-x11-extra-1.03.01-0-all.deb
   pkg install ./termux-x11-extra.deb
   ```

The app uses the Android package name `com.termux.x11.extra`. It can be installed alongside the original Termux:X11 app; each app keeps its own settings. The companion uses separate commands, so it does not replace the original Termux:X11 commands.

See the [releases page](https://github.com/moio9/termux-x11-extra/releases) for downloads and version notes.

## Start an application

Start the X server in Termux, then open **Termux:X11-Extra** on Android:

```sh
termux-x11-extra :1 &
```

Use the same display number when starting a graphical application. For a quick test:

```sh
pkg install xterm
DISPLAY=:1 xterm
```

If display `:1` is already in use, choose another number in both commands. You can also start a desktop environment, for example:

```sh
termux-x11-extra :1 -xstartup "xfce4-session"
```

The desktop environment must be installed separately.

## Controllers

1. Connect your controller to Android and open **Termux:X11-Extra**.
2. Open **Preferences → Controller** and enable **Forward gamepad to X11**.
3. To expose multiple controllers separately, also enable **Separate controllers in X11**.

A compatible application can use the separate controllers. Some games need an additional adapter or other game-specific setup. The setting alone does not add split-screen to a game.

For the SDL2 controller bridge, see [the controller bridge documentation](docs/controller-sdl2.md).

## Preferences

Open preferences from the app or its Android notification. To inspect or change settings from Termux:

```sh
termux-x11-extra-preference list
termux-x11-extra-preference gamepadSeparateX11Devices:true
```

## Touch controls

Press Android's Back button to show or hide the keyboard. Depending on the selected touch mode, tap to click, use two fingers to right-click or scroll, and swipe down with three fingers to show or hide the extra keys.

## Multiple displays

Use a different display number and a tag for each window:

```sh
termux-x11-extra :1 -tag first
termux-x11-extra :2 -tag second
```

To reopen a tagged window:

```sh
am start --user 0 -n com.termux.x11.extra/com.termux.x11.MainActivity -d first
```

## Proot

Start the X server in Termux and share its temporary directory with the container:

```sh
termux-x11-extra :1 &
proot-distro login ubuntu --shared-tmp
```

Inside the container, set `DISPLAY=:1` before starting a graphical application.

## Troubleshooting

- **Black screen:** try starting the server with `-legacy-drawing`.
- **Swapped colors:** try `-force-bgra`.
- **Large text or icons:** adjust your desktop environment's scaling or start the server with a different DPI, for example `termux-x11-extra :1 -dpi 120`.
- **No notification on Android 13 or newer:** allow notifications for Termux:X11-Extra in Android settings.

## Build from source

Clone the repository with its submodules:

```sh
git clone --recurse-submodules https://github.com/moio9/termux-x11-extra.git
```

The Android build uses Gradle and the Android SDK/NDK. Run `./gradlew assembleDebug` to build the app and companion packages.

# License
Released under the [GPLv3 license](https://www.gnu.org/licenses/gpl-3.0.html).

## **Please help to keep this project alive:**

**Monero:** 46Mk8t9uLY7jnBXnyHMyVARvwk1Y7jcGEQwKLN8GtGGBioncjKLgkEa33jEN2ibgkQjoFZWVwXXwsM3vzAFz4RzV7psLow6

**Bitcoin:** bc1qgxp74eza7jaf4fdw5cl3sanqvnh0cjmz0w9scz

**Ethereum:** 0xa024a505Ec24c7eA163985eC89D56e614B9AdAae

**Paypall:** https://paypal.me/moioyoyo
