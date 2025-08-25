package com.termux.x11.input;

import static com.termux.x11.MainActivity.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager; // Import necesar
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.termux.x11.LorieView;
import com.termux.x11.Prefs;
import com.termux.x11.ipc.GamepadIpc;

public class GamepadInputHandler {
    private static final String TAG = "GamepadInput";
    // ---- RUMBLE state ----
    private final android.os.Handler rumbleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long rumbleEndAt = 0L;
    private int lastAmp = 0;            // 0..255 (max(L,R) mapat)
    private int lastGamepadDeviceId = -1; // actualizat din evenimente
    private int vibDeviceId = -1;

    private LorieView lorieView;
    private Context context; // Necesar pentru InputManager și VibratorManager
    private InputManager inputManager;
    private final GamepadIpc ipc;
    private final GamepadIpc.GamepadState state;
    private boolean forwardToLorie;
    private String mode = "both";      // physical|virtual|both
    private String vibrateMode = "device"; // device|phone|both|off
    private int vibrateStrength = 255;
    private SharedPreferences sp;
    private boolean useKeybinds = false;
    private boolean keybindEnabled = true;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gamepad-TX");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private boolean isDpadKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
    }
    private void setDpadFromKey(int keyCode, boolean down) {
        if (!down) { state.dpad = 255; return; }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    state.dpad = 0; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: state.dpad = 2; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  state.dpad = 4; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  state.dpad = 6; break;
        }
    }
    private boolean acceptGamepadDevice(InputDevice device) {
        if (device == null) return true; // uneori Android nu populă device-ul, dar evenimentul e valid
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (sources & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD
                || (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD; // unele gamepaduri trimit butoane ca „tastatură”
    }
    private static final int BTN_A      = 1 << 0;
    private static final int BTN_B      = 1 << 1;
    private static final int BTN_X      = 1 << 2;
    private static final int BTN_Y      = 1 << 3;
    private static final int BTN_L1     = 1 << 4;
    private static final int BTN_R1     = 1 << 5;
    private static final int BTN_START  = 1 << 6;
    private static final int BTN_SELECT = 1 << 7;
    private static final int BTN_L3     = 1 << 8;
    private static final int BTN_R3     = 1 << 9;

    // Constructor actualizat pentru a primi LorieView și Context
    public GamepadInputHandler(Context context,
                               LorieView lorieView,
                               GamepadIpc ipc,
                               GamepadIpc.GamepadState state,
                               boolean forwardToLorie) {
        this.context = context;
        this.lorieView = lorieView;
        this.ipc = ipc;
        this.state = state;
        this.forwardToLorie = forwardToLorie;
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);

        this.sp = PreferenceManager.getDefaultSharedPreferences(context);

        if (this.lorieView != null) {
            this.lorieView.setFocusableInTouchMode(true);
            this.lorieView.requestFocus();
        }
    }

    public void reloadPrefs(Prefs prefs){
        mode = prefs.gamepadMode.get();
        vibrateMode = prefs.gamepadVibrate.get();
        vibrateStrength = prefs.gamepadVibrateStrength.get();
        forwardToLorie = prefs.gamepadForwardX11.get();

        // backend = keys ?
        String backend = prefs.gamepadInputType.get();
        useKeybinds = "keys".equalsIgnoreCase(backend);

        // opțional: când mapăm în taste, nu mai forward-uim gamepad brut în X11
        if (useKeybinds) forwardToLorie = false;

        // “Enable key remapper”
        keybindEnabled = sp.getBoolean("keybindRemapperEnabled", true);
    }
    private int bitForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_1:      return BTN_A;

            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_2:      return BTN_B;

            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_3:      return BTN_X;

            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_4:      return BTN_Y;

            case KeyEvent.KEYCODE_BUTTON_L1:     return BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:     return BTN_R1;

            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE:   return BTN_START;

            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:          return BTN_SELECT;

            case KeyEvent.KEYCODE_BUTTON_THUMBL: return BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return BTN_R3;
        }
        return 0;
    }

    private int parseKey(String v) {
        if (v == null) return 0;
        v = v.trim();
        if (v.equalsIgnoreCase("none")) return 0;

        switch (v.toUpperCase()) {
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "ENTER": return KeyEvent.KEYCODE_ENTER;
            case "SPACE": return KeyEvent.KEYCODE_SPACE;
            case "TAB": return KeyEvent.KEYCODE_TAB;
            case "BACKSPACE": return KeyEvent.KEYCODE_DEL;
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
        }

        if (v.length() == 1) {
            char c = v.charAt(0);
            if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        }
        return 0;
    }

    private int mapForGamepadKeycode(int gamepadKeycode) {
        switch (gamepadKeycode) {
            case KeyEvent.KEYCODE_BUTTON_A:   return parseKey(sp.getString("keybind_btn_a", "none"));
            case KeyEvent.KEYCODE_BUTTON_B:   return parseKey(sp.getString("keybind_btn_b", "none"));
            case KeyEvent.KEYCODE_BUTTON_X:   return parseKey(sp.getString("keybind_btn_x", "none"));
            case KeyEvent.KEYCODE_BUTTON_Y:   return parseKey(sp.getString("keybind_btn_y", "none"));

            case KeyEvent.KEYCODE_BUTTON_L1:  return parseKey(sp.getString("keybind_btn_lb", "none"));
            case KeyEvent.KEYCODE_BUTTON_R1:  return parseKey(sp.getString("keybind_btn_rb", "none"));
            case KeyEvent.KEYCODE_BUTTON_L2:  return parseKey(sp.getString("keybind_btn_lt", "none")); // digital
            case KeyEvent.KEYCODE_BUTTON_R2:  return parseKey(sp.getString("keybind_btn_rt", "none")); // digital

            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:       return parseKey(sp.getString("keybind_btn_back", "none"));
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE:return parseKey(sp.getString("keybind_btn_start", "none"));

            case KeyEvent.KEYCODE_BUTTON_THUMBL: return parseKey(sp.getString("keybind_btn_ls", "none"));
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return parseKey(sp.getString("keybind_btn_rs", "none"));

            case KeyEvent.KEYCODE_DPAD_UP:    return parseKey(sp.getString("keybind_dpad_up", "none"));
            case KeyEvent.KEYCODE_DPAD_DOWN:  return parseKey(sp.getString("keybind_dpad_down", "none"));
            case KeyEvent.KEYCODE_DPAD_LEFT:  return parseKey(sp.getString("keybind_dpad_left", "none"));
            case KeyEvent.KEYCODE_DPAD_RIGHT: return parseKey(sp.getString("keybind_dpad_right", "none"));
        }
        return 0;
    }

    private boolean emitMappedKey(int action, int outKeyCode) {
        if (outKeyCode == 0 || lorieView == null) return false;
        long now = SystemClock.uptimeMillis();
        KeyEvent mapped = new KeyEvent(now, now, action, outKeyCode, 0);
        return lorieView.dispatchKeyEvent(mapped);
    }

    private void sendAsync() {
        if (ipc == null) return;
        final GamepadIpc.GamepadState snap = new GamepadIpc.GamepadState();
        snap.buttons = state.buttons;
        snap.dpad = state.dpad;
        snap.thumb_lx = state.thumb_lx;
        snap.thumb_ly = state.thumb_ly;
        snap.thumb_rx = state.thumb_rx;
        snap.thumb_ry = state.thumb_ry;
        snap.left_trigger = state.left_trigger;
        snap.right_trigger = state.right_trigger;

        io.execute(() -> {
            try { ipc.sendState(snap); }
            catch (Throwable t) { Log.e(TAG, "sendState failed", t); }
        });
    }

    public void setupGamepadInput() {
        Log.d(TAG, "🎮 GamepadInputHandler initialized.");
        try {
            int[] ids = inputManager.getInputDeviceIds();
            for (int id : ids) {
                InputDevice d = inputManager.getInputDevice(id);
                if (d == null) continue;
                int src = d.getSources();
                if ((src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0) {
                    try {
                        android.os.Vibrator v = d.getVibrator();
                        if (v != null && v.hasVibrator()) { vibDeviceId = id; break; }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }


    public void setAxis(GamepadAxis axis, float x, float y) {
        sendGamepadAxisEvent(x, y, axis);
    }

    public void setButton(int androidKeyCode, boolean down) {
        if (down) handleKeyDown(androidKeyCode, null);
        else      handleKeyUp(androidKeyCode, null);
    }

    public boolean handleKeyDown(int keyCode, KeyEvent e) {
        if (useKeybinds && keybindEnabled) {
            int out = mapForGamepadKeycode(keyCode);
            if (out != 0) return emitMappedKey(KeyEvent.ACTION_DOWN, out);
        }

        // comportamentul vechi (forward/IPC)
        if (isDpadKey(keyCode)) { setDpadFromKey(keyCode, true); sendAsync(); return true; }
        int bit = bitForKey(keyCode);
        if (bit != 0) { state.buttons |= bit; sendAsync(); return true; }
        return false;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent e) {
        if (useKeybinds && keybindEnabled) {
            int out = mapForGamepadKeycode(keyCode);
            if (out != 0) return emitMappedKey(KeyEvent.ACTION_UP, out);
        }

        // comportamentul vechi (forward/IPC)
        if (isDpadKey(keyCode)) { setDpadFromKey(keyCode, false); sendAsync(); return true; }
        int bit = bitForKey(keyCode);
        if (bit != 0) { state.buttons &= ~bit; sendAsync(); return true; }
        return false;
    }

    public boolean handleGenericMotionEvent(MotionEvent event) {
        if (event == null) return false;
        final int src = event.getSource();
        final InputDevice dev = event.getDevice();

        // 1) DPAD ca HAT: procesează mereu dacă există pe event (indiferent de sursă)
        float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        // Dacă e orice mișcare sau eram într-o stare DPAD != neutru, actualizează
        if (hx != 0f || hy != 0f || state.dpad != 255) {
            sendGamepadAxisEvent(hx, hy, GamepadAxis.DPAD);
        }

        // 2) Stick-uri + triggere doar dacă sursa e joystick/gamepad
        if ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {

            // LEFT STICK
            float lx = event.getAxisValue(MotionEvent.AXIS_X);
            float ly = event.getAxisValue(MotionEvent.AXIS_Y);
            sendGamepadAxisEvent(lx, ly, GamepadAxis.LEFT_STICK);

            // RIGHT STICK - încearcă Z/RZ, fallback pe RX/RY
            float rx = event.getAxisValue(MotionEvent.AXIS_Z);
            float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
            if (rx == 0f && ry == 0f) {
                rx = event.getAxisValue(MotionEvent.AXIS_RX);
                ry = event.getAxisValue(MotionEvent.AXIS_RY);
            }
            sendGamepadAxisEvent(rx, ry, GamepadAxis.RIGHT_STICK);

            // TRIGGERS
            float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            sendGamepadAxisEvent(lt, rt, GamepadAxis.TRIGGERS);

            return true;
        }

        // dacă am apucat să setăm DPAD din HAT, consideră handled
        return (hx != 0f || hy != 0f);
    }

    private void sendGamepadButtonEvent(int button, boolean pressed) {
        if (forwardToLorie && lorieView != null) {
            lorieView.sendGamepadEvent(button, pressed, 0f, 0f, 0);
        }
        // update state & trimite prin IPC
        int bit = bitForKey(button);
        if (bit != 0) {
            if (pressed) state.buttons |= bit; else state.buttons &= ~bit;
            sendAsync();
        }
        Log.d(TAG, "keyCode=" + button + " -> bit=" + bit + " pressed=" + pressed + " srcForward=" + forwardToLorie);

    }
    private static int clampByte(float v01) {
        int b = Math.round(Math.max(0f, Math.min(1f, v01)) * 255f);
        return Math.max(0, Math.min(255, b));
    }
    private static short clampAxis(float v) {
        // v în [-1,1] -> int16
        int iv = Math.round(v * 32767f);
        if (iv < -32768) iv = -32768;
        if (iv >  32767) iv =  32767;
        return (short)iv;
    }
    private static int dpadFromHat(float x, float y) {
        final float d = 0.5f;
        if (Math.abs(x) < d && Math.abs(y) < d) return 255;
        boolean up = y < -d, down = y > d, left = x < -d, right = x > d;
        if (up && !left && !right) return 0;
        if (up && right) return 1;
        if (right && !up && !down) return 2;
        if (down && right) return 3;
        if (down && !left && !right) return 4;
        if (down && left) return 5;
        if (left && !up && !down) return 6;
        if (up && left) return 7;
        return 255;
    }
    private void sendGamepadAxisEvent(float axisX, float axisY, GamepadAxis axis) {
        if (forwardToLorie && lorieView != null) {
            lorieView.sendGamepadEvent(0, false, axisX, axisY, axis.getId());
        }
        // update state & trimite prin IPC
        switch (axis) {
            case LEFT_STICK:
                state.thumb_lx = clampAxis(axisX);
                state.thumb_ly = clampAxis(axisY);
                break;
            case RIGHT_STICK:
                state.thumb_rx = clampAxis(axisX);
                state.thumb_ry = clampAxis(axisY);
                break;
            case TRIGGERS:
                state.left_trigger  = clampByte(axisX); // L
                state.right_trigger = clampByte(axisY); // R
                break;
            case DPAD:
                state.dpad = dpadFromHat(axisX, axisY);
                break;
        }
        if (ipc != null) sendAsync();
        Log.d(TAG, "🎮 Axis event for " + axis.name() + " -> X: " + axisX + ", Y: " + axisY);
    }

    public void rumble(int left, int right, int durationMs) {
        int baseAmp = Math.max(left, right) >>> 8;
        int userAmp = Math.max(0, Math.min(255, vibrateStrength));
        int amp = (baseAmp * userAmp) / 255; // 0..255

        if (amp <= 0 || durationMs <= 0) { cancelRumble(); return; }
        if ("off".equals(vibrateMode)) { cancelRumble(); return; }

        boolean tgtDev   = "device".equals(vibrateMode) || "both".equals(vibrateMode);
        boolean tgtPhone = "phone".equals(vibrateMode)  || "both".equals(vibrateMode);

        android.os.Vibrator devVib = tgtDev ? getControllerVibrator() : null;
        android.os.Vibrator phVib  = tgtPhone ? getPhoneVibrator() : null;

        // pornește vibrația
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            VibrationEffect eff = VibrationEffect.createOneShot(
                    Math.max(10, durationMs),
                    Math.max(1, Math.min(255, amp))
            );
            if (devVib != null && devVib.hasVibrator()) devVib.vibrate(eff);
            if (phVib  != null && phVib.hasVibrator())  phVib.vibrate(eff);
        } else {
            if (devVib != null && devVib.hasVibrator()) devVib.vibrate(Math.max(10, durationMs));
            if (phVib  != null && phVib.hasVibrator())  phVib.vibrate(Math.max(10, durationMs));
        }

        // PROGRAMEAZĂ anularea corect
        lastAmp = amp;
        rumbleEndAt = android.os.SystemClock.uptimeMillis() + durationMs; // baza corectă pt Handler
        rumbleHandler.removeCallbacks(cancelRunnable);
        rumbleHandler.postDelayed(cancelRunnable, durationMs); // simplu și corect
    }

    private android.os.Vibrator getControllerVibrator() {
        try {
            InputDevice d = (vibDeviceId != -1) ? InputDevice.getDevice(vibDeviceId) : null;
            if (d == null && lastGamepadDeviceId != -1) d = InputDevice.getDevice(lastGamepadDeviceId);
            if (d != null) {
                android.os.Vibrator v = d.getVibrator();
                if (v != null && v.hasVibrator()) return v;
            }
        } catch (Throwable ignored) {}
        return null; // fallback pe telefon dacă vrei
    }

    private android.os.Vibrator getPhoneVibrator() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.VibratorManager vm = (android.os.VibratorManager)
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void cancelRumble() {
        rumbleEndAt = 0L;
        lastAmp = 0;
        rumbleHandler.removeCallbacks(cancelRunnable);

        android.os.Vibrator devVib = getControllerVibrator();
        android.os.Vibrator phVib  = getPhoneVibrator();

        if ("device".equals(vibrateMode) || "both".equals(vibrateMode)) { if (devVib != null) devVib.cancel(); }
        if ("phone".equals(vibrateMode)  || "both".equals(vibrateMode)) { if (phVib  != null) phVib.cancel();  }
    }

    private final Runnable cancelRunnable = this::cancelRumble;
    private boolean isGamepadDevice(InputDevice device) {
        if (device == null) {
            return false;
        }
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }


    // Enum pentru Axis IDs pentru lizibilitate
    public enum GamepadAxis {
        LEFT_STICK(0),
        RIGHT_STICK(1),
        TRIGGERS(2),
        DPAD(3);

        private final int id;

        GamepadAxis(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }
}