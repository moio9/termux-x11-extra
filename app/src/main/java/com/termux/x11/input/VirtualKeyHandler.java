package com.termux.x11.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;
import com.termux.x11.R;

public class VirtualKeyHandler {
    private final Context context;
    private final SparseArray<View> activeButtons = new SparseArray<>();

    public VirtualKeyHandler(Context context) {
        this.context = context;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void setupInputForButton(Button button, ViewGroup parent) {
        button.setOnTouchListener((v, event) -> {
            String tag = (String) button.getTag();
            if (tag == null) return false;

            LorieView lorieView = ((MainActivity) context).findViewById(R.id.lorieView);
            if (lorieView == null) return false;

            int pointerId = event.getPointerId(event.getActionIndex());

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    handleButtonPress(lorieView, pointerId, button);
                    break;

                case MotionEvent.ACTION_MOVE: {
                    // Verificăm dacă butonul este slideable
                    Object slideTag = button.getTag(R.id.slideable_flag);
                    if (slideTag == null || !(Boolean.TRUE.equals(slideTag))) break;

                    float x = event.getX();
                    float y = event.getY();

                    View hovered = findButtonAtPosition(parent, x + v.getX(), y + v.getY());
                    View previous = activeButtons.get(pointerId);

                    if (hovered != null && hovered != previous) {
                        if (previous != null) handleButtonRelease(lorieView, pointerId, previous);
                        handleButtonPress(lorieView, pointerId, hovered);
                    }
                    break;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    View pressed = activeButtons.get(pointerId);
                    if (pressed != null) {
                        handleButtonRelease(lorieView, pointerId, pressed);
                    }
                    break;
            }
            return true;
        });
    }

    private void handleButtonPress(LorieView lorieView, int pointerId, View button) {
        String tag = (String) button.getTag();
        if (tag == null) return;

        String[] keys = tag.split("\\+");
        for (String key : keys) {
            key = key.trim();
            int keyCode = key.startsWith("Mouse") ? getMouseButtonCode(key) : getKeyEventCode(key);
            if (keyCode != -1) {
                if (key.startsWith("Mouse")) {
                    lorieView.sendMouseEvent(0.0F, 0.0F, keyCode, true, false);
                } else {
                    lorieView.sendKeyEvent(keyCode, keyCode, true);
                }
            }
        }
        button.setPressed(true);
        activeButtons.put(pointerId, button);
    }

    private void handleButtonRelease(LorieView lorieView, int pointerId, View button) {
        String tag = (String) button.getTag();
        if (tag == null) return;

        String[] keys = tag.split("\\+");
        for (String key : keys) {
            key = key.trim();
            int keyCode = key.startsWith("Mouse") ? getMouseButtonCode(key) : getKeyEventCode(key);
            if (keyCode != -1) {
                if (key.startsWith("Mouse")) {
                    lorieView.sendMouseEvent(0.0F, 0.0F, keyCode, false, false);
                } else {
                    lorieView.sendKeyEvent(keyCode, keyCode, false);
                }
            }
        }
        button.setPressed(false);
        activeButtons.remove(pointerId);
    }


    private View findButtonAtPosition(ViewGroup parent, float x, float y) {
        int[] parentLocation = new int[2];
        parent.getLocationOnScreen(parentLocation);
        int touchX = (int) (x + parentLocation[0]);
        int touchY = (int) (y + parentLocation[1]);

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button) {
                int[] childLocation = new int[2];
                child.getLocationOnScreen(childLocation);
                int left = childLocation[0];
                int top = childLocation[1];
                int right = left + child.getWidth();
                int bottom = top + child.getHeight();

                if (touchX >= left && touchX <= right && touchY >= top && touchY <= bottom) {
                    Log.d("TOUCH", "Found button: " + ((Button) child).getText());
                    Object slideTag = child.getTag(R.id.slideable_flag);
                    if (slideTag == null || !(Boolean.TRUE.equals(slideTag))) return null;
                    return child;
                }
            }
        }
        return null;
    }

    private int getMouseButtonCode(String tag) {
        switch (tag) {
            case "Mouse_Left": return 1;
            case "Mouse_Middle": return 2;
            case "Mouse_Right": return 3;
            default: return 0;
        }
    }

    private int getKeyEventCode(String key) {
        if (key == null) return -1;
        switch (key) {
            case "A": return 30;
            case "B": return 48;
            case "C": return 46;
            case "D": return 32;
            case "E": return 18;
            case "F": return 33;
            case "G": return 34;
            case "H": return 35;
            case "I": return 23;
            case "J": return 36;
            case "K": return 37;
            case "L": return 38;
            case "M": return 50;
            case "N": return 49;
            case "O": return 24;
            case "P": return 25;
            case "Q": return 16;
            case "R": return 19;
            case "S": return 31;
            case "T": return 20;
            case "U": return 22;
            case "V": return 47;
            case "W": return 17;
            case "X": return 45;
            case "Y": return 21;
            case "Z": return 44;
            case "0": return 11;
            case "1": return 2;
            case "2": return 3;
            case "3": return 4;
            case "4": return 5;
            case "5": return 6;
            case "6": return 7;
            case "7": return 8;
            case "8": return 9;
            case "9": return 10;
            case "Space": return 57;
            case "Enter": return 28;
            case "Backspace": return 14;
            case "Tab": return 15;
            case "Escape": return 1;
            case "Delete": return 111;
            case "Insert": return 110;
            case "Home": return 102;
            case "End": return 107;
            case "Page Up": return 104;
            case "Page Down": return 109;
            case "↑": return 103;
            case "↓": return 108;
            case "←": return 105;
            case "→": return 106;
            case "Ctrl": return 29;
            case "Shift": return 42;
            case "Alt": return 56;
            case "F1": return 59;
            case "F2": return 60;
            case "F3": return 61;
            case "F4": return 62;
            case "F5": return 63;
            case "F6": return 64;
            case "F7": return 65;
            case "F8": return 66;
            case "F9": return 67;
            case "F10": return 68;
            case "F11": return 87;
            case "F12": return 88;
            case "`": return 41;
            case "-": return 12;
            case "=": return 13;
            case "[": return 26;
            case "]": return 27;
            case "\\": return 43;
            case ";": return 39;
            case "'": return 40;
            case ",": return 51;
            case ".": return 52;
            case "/": return 53;
            default: return -1;
        }
    }
}