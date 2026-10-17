package com.termux.x11.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.Display;
import android.view.InputDevice;

/** Watches physical input devices and presentation displays without polling. */
public final class TouchControlsAutoHide implements InputManager.InputDeviceListener,
        DisplayManager.DisplayListener {
    private final InputManager inputManager;
    private final DisplayManager displayManager;
    private final Runnable onDevicesChanged;

    public TouchControlsAutoHide(Context context, Runnable onDevicesChanged) {
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        this.onDevicesChanged = onDevicesChanged;
    }

    public void start(Handler handler) {
        if (inputManager != null) inputManager.registerInputDeviceListener(this, handler);
        if (displayManager != null) displayManager.registerDisplayListener(this, handler);
    }

    public void stop() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
    }

    public boolean shouldHide(boolean withController, boolean withKeyboardMouse,
                              boolean withExternalDisplay) {
        if (withExternalDisplay && displayManager != null) {
            for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) return true;
            }
        }

        boolean keyboard = false;
        boolean mouse = false;
        if ((withController || withKeyboardMouse) && inputManager != null) {
            for (int id : inputManager.getInputDeviceIds()) {
                InputDevice device = inputManager.getInputDevice(id);
                if (device == null || device.isVirtual()) continue;
                if (withController && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                        || device.supportsSource(InputDevice.SOURCE_JOYSTICK))) return true;
                if (withKeyboardMouse && device.isExternal()) {
                    keyboard |= device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                            && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
                    mouse |= device.supportsSource(InputDevice.SOURCE_MOUSE)
                            || device.supportsSource(InputDevice.SOURCE_TOUCHPAD);
                }
            }
        }
        return withKeyboardMouse && keyboard && mouse;
    }

    @Override public void onInputDeviceAdded(int id) { onDevicesChanged.run(); }
    @Override public void onInputDeviceRemoved(int id) { onDevicesChanged.run(); }
    @Override public void onInputDeviceChanged(int id) { onDevicesChanged.run(); }
    @Override public void onDisplayAdded(int id) { onDevicesChanged.run(); }
    @Override public void onDisplayRemoved(int id) { onDevicesChanged.run(); }
    @Override public void onDisplayChanged(int id) { onDevicesChanged.run(); }
}
