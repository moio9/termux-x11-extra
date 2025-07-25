package com.termux.x11.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;
import com.termux.x11.R;
import com.termux.x11.input.GamepadInputHandler;

import java.util.Arrays;
import java.util.List;

public class VirtualKeyHandler {
    private final Context context;
    private final SparseArray<View> activeButtons = new SparseArray<>();

    private GamepadInputHandler gamepadHandler;
    private float lastTouchX;
    private float lastTouchY;
    private Boolean isMouseTrackingActive = false;

    public VirtualKeyHandler(Context context) {
        this.context = context;
        gamepadHandler = new GamepadInputHandler(context);
        gamepadHandler.setupGamepadInput();
    }


    @SuppressLint("ClickableViewAccessibility")
    public void setupInputForButton(Button button, ViewGroup parent) {
        button.setOnTouchListener((v, event) -> {
            String tag = (String) button.getTag();
            if (tag == null) return false;

            LorieView lorieView = ((MainActivity) context).findViewById(R.id.lorieView);
            if (lorieView == null) return false;

            int pointerId = event.getPointerId(event.getActionIndex());
            boolean isToggleable = Boolean.TRUE.equals(button.getTag(R.id.toggleable_flag));
            boolean isSlideable = Boolean.TRUE.equals(button.getTag(R.id.slideable_flag));

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (Arrays.asList(tag.split("\\+")).contains("Mouse_Track")) {
                        lastTouchX = event.getRawX();
                        lastTouchY = event.getRawY();
                        isMouseTrackingActive = true;
                        return true;
                    }

                    if (isSlideable) {
                        handleButtonPress(lorieView, pointerId, button);
                        activeButtons.put(pointerId, button);
                    } else if (isToggleable) {
                        if (button.isSelected()) {
                            handleButtonRelease(lorieView, pointerId, button);
                            updateToggleVisual(button, true);
                            button.setSelected(false);
                        } else {
                            handleButtonPress(lorieView, pointerId, button);
                            updateToggleVisual(button, false);
                            button.setSelected(true);
                        }
                    } else {
                        handleButtonPress(lorieView, pointerId, button);
                        activeButtons.put(pointerId, button);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (tag.contains("Mouse_Track") && isMouseTrackingActive) {
                        float currentX = event.getRawX();
                        float currentY = event.getRawY();

                        float deltaX = currentX - lastTouchX;
                        float deltaY = currentY - lastTouchY;

                        if (Math.abs(deltaX) < 0.5f && Math.abs(deltaY) < 0.5f) return true;

                        lorieView.sendMouseEvent(deltaX, deltaY, 0, false, true);

                        lastTouchX = currentX;
                        lastTouchY = currentY;
                    }

                    if (tag.equals("Gamepad_LS") || tag.equals("Gamepad_RS")) {
                        float centerX = v.getX() + v.getWidth() / 2f;
                        float centerY = v.getY() + v.getHeight() / 2f;

                        float dx = (event.getRawX() - centerX) / (v.getWidth() / 2f);
                        float dy = (event.getRawY() - centerY) / (v.getHeight() / 2f);

                        dx = Math.max(-1f, Math.min(1f, dx));
                        dy = Math.max(-1f, Math.min(1f, dy));

                        int stickId = tag.contains("RS") ? 1 : 0;

                        lorieView.sendGamepadEvent(0, true, dx, dy, stickId);
                    }
                    if (tag.equals("Gamepad_LT") || tag.equals("Gamepad_RT")) {
                        int stickId = 2;
                        float value = 0;

                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                                event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN ||
                                event.getActionMasked() == MotionEvent.ACTION_MOVE) {

                            float pos = event.getY() / v.getHeight();
                            value = Math.max(0f, Math.min(1f, -pos));
                            lorieView.sendGamepadEvent(0, true, tag.equals("Gamepad_LT") ? value : 0, tag.equals("Gamepad_RT") ? value : 0, stickId);
                            return true;
                        }

                        if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                                event.getActionMasked() == MotionEvent.ACTION_POINTER_UP ||
                                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {

                            lorieView.sendGamepadEvent(0, false, 0, 0, stickId);
                            return true;
                        }
                    }

                    if (isSlideable) {
                        float x = event.getX();
                        float y = event.getY();

                        View hovered = findButtonAtPosition(parent, x + v.getX(), y + v.getY());
                        View previous = activeButtons.get(pointerId);

                        if (hovered != null && hovered != previous) {
                            if (previous != null) handleButtonRelease(lorieView, pointerId, previous);
                            handleButtonPress(lorieView, pointerId, hovered);
                            activeButtons.put(pointerId, hovered);
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (tag.contains("Mouse_Track")) {
                        isMouseTrackingActive = false;
                        lastTouchX = -1;
                        lastTouchY = -1;
                    }

                    if (tag.equals("Gamepad_LS") || tag.equals("Gamepad_RS")) {
                        int stickId = tag.contains("RS") ? 1 : 0;
                        lorieView.sendGamepadEvent(0, false, 0, 0, stickId);
                    }
                    if (!isToggleable || isSlideable) {
                        View pressed = activeButtons.get(pointerId);
                        if (pressed != null) {
                            handleButtonRelease(lorieView, pointerId, pressed);
                            activeButtons.remove(pointerId);
                        }
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
        List<String> keyList = Arrays.asList(keys);
        if (keyList.size() == 1 && keyList.contains("Mouse_Track")) return;
        for (String key : keys) {
            key = key.trim();
            if (key.contains(":")) key = key.split(":")[0];

            if (key.startsWith("Gamepad_")) {
                int code = getGamepadKeyCode(key);
                int id = getGamepadButtonCode(key);
                if (code != -1) {
                    lorieView.sendGamepadEvent(id, true, 0,0,0);
                } else {
                    handleGamepadAnalogPress(key, lorieView);
                }
            } else if (key.startsWith("Mouse")) {
                if (!key.equals("Mouse_Track")) {
                    int code = getMouseButtonCode(key);
                    if (code != -1) {
                        lorieView.sendMouseEvent(0.0F, 0.0F, code, true, false);
                    }
                }
            }
            else {
                int code = getKeyEventCode(key);
                if (code != -1) {
                    lorieView.sendKeyEvent(code, code, true);
                }
            }
        }

        button.setPressed(true);
    }


    private void handleButtonRelease(LorieView lorieView, int pointerId, View button) {
        String tag = (String) button.getTag();
        if (tag == null) return;

        String[] keys = tag.split("\\+");
        List<String> keyList = Arrays.asList(keys);
        if (keyList.size() == 1 && keyList.contains("Mouse_Track")) return;
        for (String key : keys) {
            key = key.trim();
            if (key.contains(":")) key = key.split(":")[0];

            if (key.startsWith("Gamepad_")) {
                int code = getGamepadKeyCode(key);
                int id = getGamepadButtonCode(key);
                if (code != -1) {
                    lorieView.sendGamepadEvent(id, false, 0,0,0);
                } else {
                    handleGamepadAnalogRelease(key, lorieView);
                }
            } else if (key.startsWith("Mouse")) {
                if (!key.equals("Mouse_Track")) {
                    int code = getMouseButtonCode(key);
                    if (code != -1) {
                        lorieView.sendMouseEvent(0.0F, 0.0F, code, false, false);
                    }
                }

            }
            if (key.equals("Gamepad_LT")) {
                lorieView.sendGamepadEvent(0, false, 0, 0, 2);
            } else if (key.equals("Gamepad_RT")) {
                lorieView.sendGamepadEvent(0, false, 0, 0, 2);
            }
            else {
                int code = getKeyEventCode(key);
                if (code != -1) {
                    lorieView.sendKeyEvent(code, code, false);
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

    private void updateToggleVisual(Button button, boolean selected) {
        button.animate()
            .scaleX(selected ? 1.1f : 1f)
            .scaleY(selected ? 1.1f : 1f)
            .setDuration(120)
            .start();

        Drawable bg = button.getBackground();
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).mutate(); // evită efectul global
            ((GradientDrawable) bg).setColor(selected ? Color.parseColor("#33AA33") : Color.GRAY);
        }
    }


    private int handleGamepadAnalogPress(String key, LorieView lorie) {
        switch (key) {
            case "Gamepad_LS_Left": lorie.sendGamepadEvent(0, false, -1,0,0); return -1;
            case "Gamepad_LS_Right": lorie.sendGamepadEvent(0, false, 1,0,0); return -1;
            case "Gamepad_LS_UP": lorie.sendGamepadEvent(0, false, 0,1,0); return -1;
            case "Gamepad_LS_Down": lorie.sendGamepadEvent(0, false, 0,-1,0); return -1;
            case "Gamepad_RS_Left": lorie.sendGamepadEvent(0, false, -1,0,1); return -1;
            case "Gamepad_RS_Right": lorie.sendGamepadEvent(0, false, 1,0,1); return -1;
            case "Gamepad_RS_Up": lorie.sendGamepadEvent(0, false, 0,1,1); return -1;
            case "Gamepad_RS_Down": lorie.sendGamepadEvent(0, false, 0,-1,1); return -1;
            case "Gamepad_LT_Max": lorie.sendGamepadEvent(0, false, 1,0,2); return -1;
            case "Gamepad_RT_Max": lorie.sendGamepadEvent(0, false, 0,1,2); return -1;
            case "Gamepad_DPad_Left": lorie.sendGamepadEvent(0, false, -1,0,3); return -1;
            case "Gamepad_DPad_Right": lorie.sendGamepadEvent(0, false, 1,0,3); return -1;
            case "Gamepad_DPad_Up": lorie.sendGamepadEvent(0, false, 0,1,3); return -1;
            case "Gamepad_DPad_Down": lorie.sendGamepadEvent(0, false, 0,-1,3); return -1;
            case "Gamepad_DPad": return -1;
            default: return -1;
        }
    }

    private int handleGamepadAnalogRelease(String key, LorieView lorie) {
        switch (key) {
            case "Gamepad_LS_Left":
            case "Gamepad_LS_Right":
            case "Gamepad_LS_UP":
            case "Gamepad_LS_Down":
                lorie.sendGamepadEvent(0, false, 0, 0, 0); return -1;

            case "Gamepad_RS_Left":
            case "Gamepad_RS_Right":
            case "Gamepad_RS_Up":
            case "Gamepad_RS_Down":
                lorie.sendGamepadEvent(0, false, 0, 0, 1); return -1;

            case "Gamepad_LT_Max":
            case "Gamepad_RT_Max":
                lorie.sendGamepadEvent(0, false, 0, 0, 2); return -1;

            case "Gamepad_DPad_Left":
            case "Gamepad_DPad_Right":
            case "Gamepad_DPad_Up":
            case "Gamepad_DPad_Down":
                lorie.sendGamepadEvent(0, false, 0, 0, 3); return -1;

            default: return -1;
        }
    }

    private int getGamepadKeyCode(String key) {
        switch (key) {
            case "Gamepad_A": return 48;
            case "Gamepad_B": return 49;
            case "Gamepad_Y": return 51;
            case "Gamepad_X": return 52;
            case "Gamepad_LB": return 54;
            case "Gamepad_RB": return 55;
            case "Gamepad_Select": return 58;
            case "Gamepad_Start": return 59;
            case "Gamepad_Home": return 62;

            default: return -1;
        }
    }

    private int getGamepadButtonCode(String key) {
        switch (key) {
            case "Gamepad_A": return 1;
            case "Gamepad_B": return 2;
            case "Gamepad_Y": return 3;
            case "Gamepad_X": return 4;
            case "Gamepad_LB": return 5;
            case "Gamepad_RB": return 6;
            case "Gamepad_Select": return 7;
            case "Gamepad_Start": return 8;
            case "Gamepad_Home": return 9;

            default: return -1;
        }
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
            case "A":
                return 30;
            case "B":
                return 48;
            case "C":
                return 46;
            case "D":
                return 32;
            case "E":
                return 18;
            case "F":
                return 33;
            case "G":
                return 34;
            case "H":
                return 35;
            case "I":
                return 23;
            case "J":
                return 36;
            case "K":
                return 37;
            case "L":
                return 38;
            case "M":
                return 50;
            case "N":
                return 49;
            case "O":
                return 24;
            case "P":
                return 25;
            case "Q":
                return 16;
            case "R":
                return 19;
            case "S":
                return 31;
            case "T":
                return 20;
            case "U":
                return 22;
            case "V":
                return 47;
            case "W":
                return 17;
            case "X":
                return 45;
            case "Y":
                return 21;
            case "Z":
                return 44;
            case "0":
                return 11;
            case "1":
                return 2;
            case "2":
                return 3;
            case "3":
                return 4;
            case "4":
                return 5;
            case "5":
                return 6;
            case "6":
                return 7;
            case "7":
                return 8;
            case "8":
                return 9;
            case "9":
                return 10;
            case "Space":
                return 57;
            case "Enter":
                return 28;
            case "Backspace":
                return 14;
            case "Tab":
                return 15;
            case "Escape":
                return 1;
            case "Delete":
                return 111;
            case "Insert":
                return 110;
            case "Home":
                return 102;
            case "End":
                return 107;
            case "Page Up":
                return 104;
            case "Page Down":
                return 109;
            case "↑":
                return 103;
            case "↓":
                return 108;
            case "←":
                return 105;
            case "→":
                return 106;
            case "Ctrl":
                return 29;
            case "Shift":
                return 42;
            case "Alt":
                return 56;
            case "F1":
                return 59;
            case "F2":
                return 60;
            case "F3":
                return 61;
            case "F4":
                return 62;
            case "F5":
                return 63;
            case "F6":
                return 64;
            case "F7":
                return 65;
            case "F8":
                return 66;
            case "F9":
                return 67;
            case "F10":
                return 68;
            case "F11":
                return 87;
            case "F12":
                return 88;
            case "`":
                return 41;
            case "-":
                return 12;
            case "=":
                return 13;
            case "[":
                return 26;
            case "]":
                return 27;
            case "\\":
                return 43;
            case ";":
                return 39;
            case "'":
                return 40;
            case "，":
                return 51;
            case ".":
                return 52;
            case "/":
                return 53;
            case "◆":
                return 125;
            default:
                return -1;
        }
    }
}