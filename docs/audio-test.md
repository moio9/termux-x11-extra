# Audio capture prototype

1. Install the test APK and enable **Settings → Audio → Play audio through Termux:X11**.
2. With PulseAudio running in Termux, run from this checkout:

   ```sh
   python3 tools/termux-x11-audio.py start
   ```

3. Play audio in a game, then record the screen with **device/internal audio** enabled. Check the recording for sound and delay. On Android 14+, try whole-screen capture if single-app capture omits audio.
4. Restore normal output when done:

   ```sh
   python3 tools/termux-x11-audio.py stop
   ```

The helper temporarily routes playback to a dedicated PulseAudio sink and exposes its monitor only on `127.0.0.1:4714`. Termux:X11 plays 48 kHz stereo PCM16 through Android AudioTrack with capture allowed. It does not record the microphone.

Turning off the app option stops playback; run the helper with `stop` to restore PulseAudio routing too. Closing Termux:X11 while routing is enabled leaves that route silent until the app reconnects or the helper is stopped. There is no background playback service in this prototype. Latency and recorder compatibility still need device testing.

Debug log tag: `X11Audio`.
