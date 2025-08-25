package com.termux.x11;

import static com.termux.x11.MainActivity.prefs;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Display;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.x11.input.VirtualKeyHandler;
import com.termux.x11.MainActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


public class VirtualKeyMapperActivity extends AppCompatActivity {
    private FrameLayout buttonContainer;
    private static String SlideColor = "#FFFF99";
    private static Float offset = 120F;

    private FrameLayout frameLayoutButtons;

    private final List<Button> selectedButtons = new ArrayList<>();
    private float dX, dY; // For multi-drag
    private Button dragStartButton; // To track which button started the drag

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_key_mapper);

        buttonContainer = findViewById(R.id.buttonContainer);
        Button addNewKeyButton = findViewById(R.id.addNewKeyButton);
        Button savePresetButton = findViewById(R.id.savePresetButton);
        Button loadPresetButton = findViewById(R.id.loadPresetButton);

        addNewKeyButton.setOnClickListener(v -> addNewButton(null));
        savePresetButton.setOnClickListener(v -> showSavePresetDialog());

        // === FIX: obține instanța MainActivity și trece obiectele către VirtualKeyHandler
        MainActivity act = MainActivity.getInstance();
        VirtualKeyHandler virtualKeyHandler = new VirtualKeyHandler(
                this,
                act != null ? act.getLorieView()     : null,
                act != null ? act.getGamepadIpc()    : null,
                act != null ? act.getGamepadState()  : null,
                act != null ? act.getGamepadHandler(): null
        );

        loadPresetButton.setOnClickListener(v -> showLoadPresetDialog(buttonContainer, virtualKeyHandler));

        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        showLoadPresetDialog(buttonContainer, virtualKeyHandler);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // === FIX: elimină construcția cu constructorul vechi
        if (getIntent().getBooleanExtra("open_load_preset", false)) {
            showLoadPresetDialog(buttonContainer, virtualKeyHandler);
        }

        if (buttonContainer == null) {
            Log.e("DEBUG", "buttonContainer NU a fost găsit!");
        }
    }


    private void addNewButton(Button button) {
        if (button == null) {
            Button newButton = new Button(this);
            int newId = generateUniqueButtonId();
            newButton.setId(newId);
            newButton.setText("Key " + newId);
            newButton.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            button = newButton;
        }

        enableDrag(button);
        setupSelectionLogic(button);
        registerForContextMenu(button);

        buttonContainer.addView(button);
    }


    private void setupSelectionLogic(Button button) {
        button.setOnClickListener(v -> toggleSelection(button));
    }

    private void toggleSelection(Button button) {
        if (selectedButtons.contains(button)) {
            selectedButtons.remove(button);
            button.setAlpha(1.0f);
        } else {
            selectedButtons.add(button);
            button.setAlpha(0.5f);
        }
    }

    private void enableDrag(View view) {
        final int DRAG_THRESHOLD = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();

        view.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        isDragging = false;
                        return false; // Allow other events

                    case MotionEvent.ACTION_MOVE:
                        if (isDragging) {
                            // Continue existing drag
                            float deltaX = event.getRawX() - startX;
                            float deltaY = event.getRawY() - startY;

                            for (Button btn : selectedButtons) {
                                btn.setX(btn.getX() + deltaX);
                                btn.setY(btn.getY() + deltaY);
                                saveButtonSettings(btn, offset);
                            }

                            startX = event.getRawX();
                            startY = event.getRawY();
                            return true;
                        }
                        else if (isDragThresholdExceeded(event)) {
                            // Start new drag
                            isDragging = true;
                            v.cancelLongPress(); // Cancel potential long-press
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            isDragging = false;
                            return true; // Consume event
                        }
                        return false;
                }
                return false;
            }

            private boolean isDragThresholdExceeded(MotionEvent event) {
                float deltaX = Math.abs(event.getRawX() - startX);
                float deltaY = Math.abs(event.getRawY() - startY);
                return (deltaX > DRAG_THRESHOLD || deltaY > DRAG_THRESHOLD);
            }
        });

        registerForContextMenu(view);
    }

    private void alignSelectedButtonsX() {
        if (selectedButtons.size() < 2) return;
        float targetX = selectedButtons.get(0).getX();
        for (Button btn : selectedButtons) {
            btn.setX(targetX);
            saveButtonSettings(btn, offset);
        }
    }

    private void alignSelectedButtonsY() {
        if (selectedButtons.size() < 2) return;
        float targetY = selectedButtons.get(0).getY();
        for (Button btn : selectedButtons) {
            btn.setY(targetY);
            saveButtonSettings(btn, offset);
        }
    }

    private int generateUniqueButtonId() {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        int lastUsedId = prefs.getInt("lastUsedId", 0);
        int newId = lastUsedId + 1;

        prefs.edit().putInt("lastUsedId", newId).apply();

        return newId;
    }


    private String saveButtonSettings(Button button) {
        return saveButtonSettings(button, 0F);
    }
    private String saveButtonSettings(Button button, Float offset) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> buttonData = new HashSet<>(prefs.getStringSet("button_data", new HashSet<>()));

        int id = button.getId();
        String tag = (String) button.getTag();
        String text = button.getText().toString();
        float x = button.getX();
        float y = button.getY() + offset;
        int width = button.getWidth();
        int height = button.getHeight();
        int alpha = button.getBackground().getAlpha();
        boolean isSlideable = Boolean.TRUE.equals(button.getTag(R.id.slideable_flag));
        boolean isToggleable = Boolean.TRUE.equals(button.getTag(R.id.toggleable_flag));

        String line = id + "," + x + "," + y + "," + width + "," + height + "," + alpha + "," + tag + "," + text + "," + isSlideable + "," + isToggleable;

        buttonData.removeIf(data -> data.startsWith(id + ","));

        buttonData.add(line);

        editor.putStringSet("button_data", buttonData);
        editor.apply();

        Log.d("DEBUG", "Salvăm butonul: id=" + id + ", tag=" + tag + ", text=" + text);
        return line;
    }


    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        getMenuInflater().inflate(R.menu.button_options_menu, menu);
        menu.setHeaderTitle("Button Options");
        if (selectedButtons.size() < 2) {
            menu.findItem(R.id.action_align_x).setVisible(false);
            menu.findItem(R.id.action_align_y).setVisible(false);
        } else {
            menu.findItem(R.id.action_align_x).setVisible(true);
            menu.findItem(R.id.action_align_y).setVisible(true);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (selectedButtons != null && !selectedButtons.isEmpty()) {
            if (item.getItemId() == R.id.action_delete) {
                for (Button button : selectedButtons) {
                    buttonContainer.removeView(button);
                    removeButtonFromStorage(button.getId());
                }
                selectedButtons.clear();
                return true;

            } else if (item.getItemId() == R.id.action_transparency) {
                showTransparencyDialog(selectedButtons);
                return true;

            } else if (item.getItemId() == R.id.action_resize) {
                showResizeDialog(selectedButtons);
                return true;

            } else if (item.getItemId() == R.id.action_rename) {
                showRenameDialog(selectedButtons);
                return true;

            } else if (item.getItemId() == R.id.action_set_input) {
                showSetInputDialog(selectedButtons);
                return true;

            } else if (item.getItemId() == R.id.action_copy) {
                copyButton(selectedButtons);
                return true;

            } else if (item.getItemId() == R.id.action_type) {
                showButtonTypeDialog(selectedButtons);
                return true;
            }else if (item.getItemId() == R.id.action_align_x) {
                alignSelectedButtonsX();
                return true;
            }
            else if (item.getItemId() == R.id.action_align_y) {
                alignSelectedButtonsY();
                return true;
            }

        }
        return super.onContextItemSelected(item);
    }

    private void showButtonTypeDialog(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        final String[] types = {"None", "Slide", "Toggle"};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Button Type");
        builder.setSingleChoiceItems(types, 0, null);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            int selected = ((AlertDialog) dialog).getListView().getCheckedItemPosition();

            for (Button button : buttons) {
                button.setTag(R.id.slideable_flag, false);
                button.setTag(R.id.toggleable_flag, false);
                Drawable defbtn = new Button(this).getBackground();
                button.setBackground(defbtn);
                button.getBackground().clearColorFilter();

                if (selected == 1) { // Slide
                    button.setTag(R.id.slideable_flag, true);
                    button.getBackground().setColorFilter(Color.parseColor(SlideColor), android.graphics.PorterDuff.Mode.MULTIPLY);
                } else if (selected == 2) { // Toggle
                    button.setTag(R.id.toggleable_flag, true);
                    applyToggleStyle(button);
                }

                saveButtonSettings(button, offset);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private static void applyToggleStyle(Button button) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(40);
        drawable.setStroke(5, Color.parseColor("#33AA33"));
        drawable.setColor(Color.GRAY);
        drawable.setAlpha(button.getBackground().getAlpha());

        button.setBackground(drawable);
    }


    private void copyButton(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        for (Button originalButton : buttons) {
            Button copiedButton = new Button(this);

            int newId = generateUniqueButtonId(); // ID unic
            copiedButton.setId(newId);
            copiedButton.setText(originalButton.getText() + " (Copy)");
            copiedButton.setX(originalButton.getX() + 50); // Offset poziție
            copiedButton.setY(originalButton.getY() + 50);
            copiedButton.setTag(originalButton.getTag());

            // Copiază dimensiunile exacte
            FrameLayout.LayoutParams originalParams = (FrameLayout.LayoutParams) originalButton.getLayoutParams();
            FrameLayout.LayoutParams newParams = new FrameLayout.LayoutParams(originalParams.width, originalParams.height);
            copiedButton.setLayoutParams(newParams);

            // Copiază background (shape + alpha)
            if (originalButton.getBackground() != null) {
                copiedButton.setBackground(originalButton.getBackground().getConstantState().newDrawable().mutate());
                copiedButton.getBackground().setAlpha(originalButton.getBackground().getAlpha());

                // Dacă e slideable, copiem și colorFilter + tag
                Object isSlideable = originalButton.getTag(R.id.slideable_flag);
                if (Boolean.TRUE.equals(isSlideable)) {
                    copiedButton.setTag(R.id.slideable_flag, true);
                    copiedButton.getBackground().setColorFilter(Color.parseColor(SlideColor), android.graphics.PorterDuff.Mode.MULTIPLY);
                }
                Object isToggleable = originalButton.getTag(R.id.toggleable_flag);
                if (Boolean.TRUE.equals(isToggleable)) {
                    copiedButton.setTag(R.id.toggleable_flag, true);
                    applyToggleStyle(copiedButton);
                }
            }

            // Activăm drag & meniul contextual
            enableDrag(copiedButton);
            setupSelectionLogic(copiedButton);
            registerForContextMenu(copiedButton);

            // Adăugăm în UI
            buttonContainer.addView(copiedButton);

            // Salvăm în preferințe
            saveButtonSettings(copiedButton, offset);
        }
        Toast.makeText(this, "✅ Button(s) copied!", Toast.LENGTH_SHORT).show();
    }



    private void removeButtonFromStorage(int buttonId) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> buttonData = new HashSet<>(prefs.getStringSet("button_data", new HashSet<>()));
        buttonData.removeIf(pos -> pos.startsWith(buttonId + ","));

        editor.putStringSet("button_data", buttonData);
        editor.apply();
    }


    private void showResizeDialog(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Resize Button(s)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView widthLabel = new TextView(this);
        widthLabel.setText("Width");
        SeekBar widthSeekBar = new SeekBar(this);
        widthSeekBar.setMax(500);
        widthSeekBar.setMin(50);
        widthSeekBar.setProgress(buttons.get(0).getWidth());

        TextView heightLabel = new TextView(this);
        heightLabel.setText("Height");
        SeekBar heightSeekBar = new SeekBar(this);
        heightSeekBar.setMax(500);
        heightSeekBar.setMin(50);
        heightSeekBar.setProgress(buttons.get(0).getHeight());

        TextView scaleLabel = new TextView(this);
        scaleLabel.setText("Scale Uniformly (Square Size)");
        SeekBar scaleSeekBar = new SeekBar(this);
        scaleSeekBar.setMax(500);
        scaleSeekBar.setMin(50);
        scaleSeekBar.setProgress(Math.max(buttons.get(0).getWidth(), buttons.get(0).getHeight()));

        TextView lockLabel = new TextView(this);
        lockLabel.setText("Lock Aspect Ratio");
        android.widget.CheckBox lockRatioCheckBox = new android.widget.CheckBox(this);
        lockRatioCheckBox.setChecked(false);

        layout.addView(widthLabel);
        layout.addView(widthSeekBar);
        layout.addView(heightLabel);
        layout.addView(heightSeekBar);
        layout.addView(scaleLabel);
        layout.addView(scaleSeekBar);
        layout.addView(lockLabel);
        layout.addView(lockRatioCheckBox);

        builder.setView(layout);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            int width = widthSeekBar.getProgress();
            int height = heightSeekBar.getProgress();
            for (Button btn : buttons) {
                btn.setLayoutParams(new FrameLayout.LayoutParams(width, height));
                saveButtonSettings(btn);
            }
        });

        builder.setNeutralButton("Scale Uniformly", (dialog, which) -> {
            int scale = scaleSeekBar.getProgress();
            for (Button btn : buttons) {
                btn.setLayoutParams(new FrameLayout.LayoutParams(scale, scale));
                saveButtonSettings(btn);
            }
        });

        builder.setNegativeButton("Reset", (dialog, which) -> {
            int resetSize = 100;
            for (Button btn : buttons) {
                btn.setLayoutParams(new FrameLayout.LayoutParams(resetSize, resetSize));
                saveButtonSettings(btn);
            }
        });

        widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (lockRatioCheckBox.isChecked()) {
                    float ratio = buttons.get(0).getHeight() / (float) buttons.get(0).getWidth();
                    heightSeekBar.setProgress(Math.round(progress * ratio));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        heightSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (lockRatioCheckBox.isChecked()) {
                    float ratio = buttons.get(0).getWidth() / (float) buttons.get(0).getHeight();
                    widthSeekBar.setProgress(Math.round(progress * ratio));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        builder.show();
    }


    private void showTransparencyDialog(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Adjust Transparency");

        SeekBar transparencySeekBar = new SeekBar(this);
        transparencySeekBar.setMax(255);
        transparencySeekBar.setMin(5);
        transparencySeekBar.setProgress(buttons.get(0).getBackground().getAlpha());

        builder.setView(transparencySeekBar);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            int alpha = transparencySeekBar.getProgress();
            for (Button btn : buttons) {
                btn.getBackground().setAlpha(alpha);
                saveButtonSettings(btn);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    private void showRenameDialog(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Key(s)");

        EditText input = new EditText(this);
        input.setHint("New name for selected");
        // Dacă e doar unul selectat, populăm cu numele curent
        if (buttons.size() == 1) input.setText(buttons.get(0).getText().toString());
        builder.setView(input);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                for (Button btn : buttons) {
                    btn.setText(newName);
                    saveButtonSettings(btn);
                }
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }



    private void savePreset(String presetKey) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (buttonContainer == null) {
            return;
        }

        Set<String> buttonData = new HashSet<>();
        for (int i = 0; i < buttonContainer.getChildCount(); i++) {
            View view = buttonContainer.getChildAt(i);
            if (view instanceof Button) {
                Button button = (Button) view;
                String data = saveButtonSettings(button, offset);
                buttonData.add(data);
            }
        }

        String displayId = getDisplayId(buttonContainer.getContext());
        editor.putStringSet(presetKey, buttonData);
        editor.putString("last_used_preset_" + displayId, presetKey);
        editor.apply();

        MainActivity instance = MainActivity.getInstance();
        if (instance != null) {
            instance.refreshLoadedPreset(true);
        }

        Toast.makeText(this, "✅ Preset saved: " + presetKey.replace("preset_", ""), Toast.LENGTH_SHORT).show();
    }


    public void showLoadPresetDialog(FrameLayout mainContainer, VirtualKeyHandler virtualKeyHandler) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        ensureEmptyPresetExists(prefs);

        String displayId = getDisplayId(buttonContainer.getContext());
        List<String> presetNames = getPresetNames(prefs);

        if (presetNames.isEmpty()) {
            Toast.makeText(this, "No presets available", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Manage Presets");

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, presetNames);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedPreset = "preset_" + presetNames.get(position);
            List<Button> buttons = loadPreset(this, selectedPreset, mainContainer, -offset);

            for (Button btn : buttons) {
                enableDrag(btn);
                setupSelectionLogic(btn);
                registerForContextMenu(btn);
            }

            prefs.edit().putString("last_used_preset_" + displayId, selectedPreset).apply();

            MainActivity instance = MainActivity.getInstance();
            if (instance != null) {
                instance.refreshLoadedPreset(true);
            }

            Toast.makeText(this, "✅ Preset loaded: " + presetNames.get(position), Toast.LENGTH_SHORT).show();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String selectedPreset = "preset_" + presetNames.get(position);
            showPresetOptionsDialog(selectedPreset, mainContainer, virtualKeyHandler, adapter, presetNames);
            return true;
        });

        builder.setView(listView);
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }


    public static List<String> getPresetNames(SharedPreferences prefs) {
        Set<String> presetKeys = prefs.getAll().keySet();
        List<String> presetNames = new ArrayList<>();

        for (String key : presetKeys) {
            if (key.startsWith("preset_")) {
                presetNames.add(key.replace("preset_", ""));
            }
        }
        return presetNames;
    }


    private void showPresetOptionsDialog(String presetKey, FrameLayout mainContainer, VirtualKeyHandler virtualKeyHandler, ArrayAdapter<String> adapter, List<String> presetNames) {
        String presetName = presetKey.replace("preset_", "");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Preset: " + presetName)
                .setItems(new String[]{"Load", "Rename", "Delete"}, (dialog, which) -> {
                    switch (which) {
                        case 0: // Load
                            loadPreset(this, presetKey, mainContainer);
                            Toast.makeText(this, "✅ Preset loaded: " + presetName, Toast.LENGTH_SHORT).show();
                            break;

                        case 1: // Rename
                            showRenamePresetDialog(presetKey, adapter, presetNames);
                            break;

                        case 2: // Delete
                            showDeletePresetDialog(presetKey, adapter, presetNames);
                            break;
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    /**
     * Rename
     */
    private void showRenamePresetDialog(String oldPresetKey, ArrayAdapter<String> adapter, List<String> presetNames) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Preset");

        final EditText input = new EditText(this);
        input.setText(oldPresetKey.replace("preset_", ""));
        builder.setView(input);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newPresetName = input.getText().toString().trim();
            if (!newPresetName.isEmpty()) {
                SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();

                Set<String> oldData = prefs.getStringSet(oldPresetKey, new HashSet<>());
                String newPresetKey = "preset_" + newPresetName;

                editor.putStringSet(newPresetKey, oldData);
                editor.remove(oldPresetKey);
                editor.apply();

                presetNames.remove(oldPresetKey.replace("preset_", ""));
                presetNames.add(newPresetName);
                adapter.notifyDataSetChanged();

                Toast.makeText(this, "✅ Preset renamed!", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    /**
     * Trash
     */
    private void showDeletePresetDialog(String presetKey, ArrayAdapter<String> adapter, List<String> presetNames) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Delete Preset");
        builder.setMessage("Are you sure you want to delete this preset?");

        builder.setPositiveButton("Delete", (dialog, which) -> {
            SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(presetKey);
            editor.apply();

            presetNames.remove(presetKey.replace("preset_", ""));
            adapter.notifyDataSetChanged();

            Toast.makeText(this, "✅ Preset deleted!", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }


    private void showSetInputDialog(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) return;
        String[] keys = {
                "== KEYBOARD ==", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
                "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "，", "=", "-", "[", "]", "\\", ";", "'", "/", ".",
                "`", // backtick
                "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
                "Space", "Enter", "Backspace", "Tab", "Escape", "Delete", "Insert", "◆",
                "Home", "End", "Page Up", "Page Down", "↑", "↓", "←", "→",
                "Alt", "Ctrl", "Shift",
                "== MOUSE ==", "Mouse_Left", "Mouse_Right", "Mouse_Middle", "Mouse_Track",
                "== GAMEPAD ==", "Gamepad_A", "Gamepad_B", "Gamepad_X", "Gamepad_Y",
                "Gamepad_LB", "Gamepad_RB", "Gamepad_LT", "Gamepad_RT",
                "Gamepad_Start", "Gamepad_Select", "Gamepad_DPad_Up", "Gamepad_DPad_Down",
                "Gamepad_DPad_Left", "Gamepad_DPad_Right", "Gamepad_LS_Left",
                "Gamepad_LS_Right", "Gamepad_LS_Up", "Gamepad_LS_Down",
                "Gamepad_RS_Left", "Gamepad_RS_Right", "Gamepad_RS_Up",
                "Gamepad_RS_Down", "Gamepad_LT_Max", "Gamepad_RT_Max",
                "Gamepad_LS", "Gamepad_RS", "Gamepad_LT", "Gamepad_RT",
                "== MISC ==", "EMPTY"
        };

        List<String> selectedKeys = new ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Input Keys");

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, keys) {
            @Override
            public boolean isEnabled(int position) {
                return !getItem(position).startsWith("==");
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                String item = getItem(position);
                if (item != null && item.trim().startsWith("==")) {
                    textView.setTextColor(Color.RED);
                    textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    view.setBackgroundColor(Color.parseColor("#ffffff"));
                } else {
                    textView.setTextColor(Color.BLACK);
                    view.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }

        };
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);


        listView.setOnItemClickListener((parent, view, position, id) -> {
            String key = keys[position];
            if (key.startsWith("==")) {
                listView.setItemChecked(position, false);
            } else {
                if (listView.isItemChecked(position)) {
                    selectedKeys.add(key);
                } else {
                    selectedKeys.remove(key);
                }
            }
        });

        builder.setView(listView);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            if (!selectedKeys.isEmpty()) {
                String tagValue = String.join("+", selectedKeys);
                for (Button btn : buttons) {
                    btn.setTag(tagValue);
                    btn.setText(tagValue);
                    saveButtonSettings(btn, offset);
                }
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }


    public static List<Button> loadPreset(Context context, FrameLayout buttonContainer) {
        return loadPreset(context, "/", buttonContainer, (float) 0);
    }

    public static List<Button> loadPreset(Context context, String presetName, FrameLayout buttonContainer) {
        return loadPreset(context, presetName, buttonContainer, (float) 0);
    }

    public static List<Button> loadPreset(Context context, String presetName, FrameLayout buttonContainer, Float offset) {
        if (buttonContainer == null) {
            Log.e("ERROR", "❌ buttonContainer este NULL!");
            return new ArrayList<>();
        }
        SharedPreferences prefs = context.getSharedPreferences("button_prefs", Context.MODE_PRIVATE);

        String screenID = getDisplayId(context);
        if (presetName.startsWith("/") || Objects.equals(presetName, "")) {
            presetName = prefs.getString("last_used_preset_" + screenID, "preset_empty");
        }

        Set<String> buttonData = prefs.getStringSet(presetName, null);
        if (buttonData == null) {
            Log.d("DEBUG", "⚠️ Presetul '" + presetName + "' nu există! Se încarcă presetul gol.");
            presetName = "preset_empty";
            buttonData = prefs.getStringSet(presetName, new HashSet<>());
        }

        List<View> toRemove = new ArrayList<>();
        for (int i = 0; i < buttonContainer.getChildCount(); i++) {
            View child = buttonContainer.getChildAt(i);
            if (child instanceof Button) {
                toRemove.add(child);
            }
        }
        for (View view : toRemove) {
            buttonContainer.removeView(view);
        }

        List<Button> buttons = new ArrayList<>();
        if (!presetName.equals("preset_empty")) {
            for (String data : buttonData) {
                String[] parts = data.split(",");
                if (parts.length < 9) continue;

                int id = Integer.parseInt(parts[0]);
                float x = Float.parseFloat(parts[1]);
                float y = Float.parseFloat(parts[2]);
                int width = Integer.parseInt(parts[3]);
                int height = Integer.parseInt(parts[4]);
                int alpha = Integer.parseInt(parts[5]);
                String tag = parts[6];
                String text = parts[7];
                boolean isSlideable = Boolean.parseBoolean(parts[8]);


                Button button = new Button(context);
                button.setText(text);
                button.setTag(tag);
                button.setId(id);
                button.setLayoutParams(new FrameLayout.LayoutParams(width, height));
                button.setX(x);
                button.setY(y+offset);
                button.getBackground().setAlpha(alpha);
                if (isSlideable) {
                    button.setTag(R.id.slideable_flag, true);
                    button.getBackground().setColorFilter(Color.parseColor(SlideColor), android.graphics.PorterDuff.Mode.MULTIPLY);
                }
                if (parts.length >= 10 && Boolean.parseBoolean(parts[9])) {
                    button.setTag(R.id.toggleable_flag, true);
                    applyToggleStyle(button);

                    if (isSlideable) {
                        button.getBackground().setColorFilter(Color.parseColor(SlideColor), android.graphics.PorterDuff.Mode.MULTIPLY);
                    }
                }


                buttonContainer.addView(button);
                buttons.add(button);
            }
        }
        return buttons;
    }



    public static void ensureEmptyPresetExists(SharedPreferences prefs) {
        String emptyPresetName = "preset_empty";
        Set<String> defaultPreset = prefs.getStringSet(emptyPresetName, null);

        if (defaultPreset == null) {
            SharedPreferences.Editor editor = prefs.edit();

            Set<String> emptyPreset = new HashSet<>();

            editor.putStringSet("Empty", emptyPreset);
            editor.apply();

        }
    }

    private void showSavePresetDialog() {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        Set<String> presetKeys = prefs.getAll().keySet();
        List<String> presetNames = new ArrayList<>();

        for (String key : presetKeys) {
            if (key.startsWith("preset_")) {
                presetNames.add(key.replace("preset_", ""));
            }
        }

        presetNames.add(0, "New Preset...");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Preset");

        builder.setItems(presetNames.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) {
                showNewPresetDialog();
            } else {
                String selectedPreset = "preset_" + presetNames.get(which);
                showSavePresetOptionsDialog(selectedPreset); // Apelezi noul dialog cu Overwrite/Merge
            }
        });


        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }


    private void showNewPresetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter New Preset Name");

        final EditText input = new EditText(this);
        input.setHint("Preset Name");

        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String presetName = input.getText().toString().trim();
            if (!presetName.isEmpty()) {
                String presetKey = "preset_" + presetName;
                savePreset(presetKey);
            } else {
                Toast.makeText(this, "Preset name cannot be empty!", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }


    private void showSavePresetOptionsDialog(String presetKey) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Preset: " + presetKey.replace("preset_", ""));

        String[] options = {"Overwrite", "Merge", "Cancel"}; // cele 3 opțiuni

        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // Overwrite
                    savePreset(presetKey);
                    break;
                case 1: // Merge
                    mergePreset(presetKey);
                    break;
                case 2: // Cancel
                    dialog.dismiss();
                    break;
            }
        });

        builder.show();
    }

    private void mergePreset(String presetKey) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        Set<String> existingData = new HashSet<>(prefs.getStringSet(presetKey, new HashSet<>()));

        for (int i = 0; i < buttonContainer.getChildCount(); i++) {
            View view = buttonContainer.getChildAt(i);
            if (view instanceof Button) {
                Button button = (Button) view;
                String data = saveButtonSettings(button, offset);
                existingData.add(data); // combină fără a elimina cele vechi
            }
        }

        prefs.edit().putStringSet(presetKey, existingData).apply();

        MainActivity instance = MainActivity.getInstance();
        if (instance != null) {
            instance.refreshLoadedPreset(true);
        }

        Toast.makeText(this, "✅ Preset merged into: " + presetKey.replace("preset_", ""), Toast.LENGTH_SHORT).show();
    }


    public static String getDisplayId(Context context) {
        String displayType = "Builtin Display"; // Implicit

        Intent intent = context.registerReceiver(null, new IntentFilter("android.intent.action.HDMI_PLUGGED"));
        boolean isHdmiConnected = (intent != null && intent.getBooleanExtra("state", false));

        if (isHdmiConnected) {
            displayType = "External Display (HDMI)";
        } else {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                for (Display display : displayManager.getDisplays()) {
                    if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        displayType = "External Display";
                        break;
                    }
                }
            }
        }

        return displayType;
    }

}
