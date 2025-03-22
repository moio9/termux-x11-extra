package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import android.view.ViewGroup;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


public class VirtualKeyMapperActivity extends AppCompatActivity {
    private FrameLayout buttonContainer;
    private static String SlideColor = "#FFFF99";
    private static Float offset = 120F;

    private View selectedButton; // Butonul selectat în meniul contextual

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

        VirtualKeyHandler virtualKeyHandler = new VirtualKeyHandler(this);
        loadPresetButton.setOnClickListener(v -> showLoadPresetDialog(buttonContainer, virtualKeyHandler));
        //loadButtons(this, virtualKeyHandler);
        //loadPreset("default");


        buttonContainer = findViewById(R.id.buttonContainer);
        if (buttonContainer == null) {
            Log.e("DEBUG", "buttonContainer NU a fost găsit!");
        }
        buttonContainer.setVisibility(View.VISIBLE);


    }


    /**
     * Adaugă un buton nou pe ecran
     */
    private void addNewButton(Button button) {
        if (button == null) {
            Button newButton = new Button(this);
            int newId = generateUniqueButtonId();  // ID unic
            newButton.setId(newId);

            newButton.setText("Key " + newId);
            newButton.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            button = newButton;
        }

        registerForContextMenu(button);
        enableDrag(button);

        button.setOnLongClickListener(v -> {
            openContextMenu(v);
            return true;
        });

        buttonContainer.addView(button);
    }


    /**
     * Permite mutarea butoanelor
     */
    private void enableDrag(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private long touchStartTime;
            private static final int LONG_PRESS_THRESHOLD = 500;
            private Handler longPressHandler = new Handler();
            private Runnable longPressRunnable = () -> view.performLongClick();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = Math.abs(event.getRawX() - (v.getX() - dX));
                        float deltaY = Math.abs(event.getRawY() - (v.getY() - dY));

                        if (deltaX > 10 || deltaY > 10) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                        }

                        v.setX(event.getRawX() + dX);
                        v.setY(event.getRawY() + dY);

                        // Salvăm poziția în timp real
                        if (v instanceof Button) {
                            saveButtonSettings((Button) v, offset);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        longPressHandler.removeCallbacks(longPressRunnable);
                        if (v instanceof Button) {
                            saveButtonSettings((Button) v, offset);
                        }
                        break;
                }
                return true;
            }
        });

        registerForContextMenu(view);
    }


    private int generateUniqueButtonId() {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        int lastUsedId = prefs.getInt("lastUsedId", 0);
        int newId = lastUsedId + 1;

        // Salvăm noul ID
        prefs.edit().putInt("lastUsedId", newId).apply();

        return newId;
    }


    /**
     * Salvează poziția butonului în SharedPreferences
     *
     * @return
     */
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

        String line = id + "," + x + "," + y + "," + width + "," + height + "," + alpha + "," + tag + "," + text + "," + isSlideable;

        // Ștergem orice linie care începe cu același ID, ca să nu avem duplicat
        buttonData.removeIf(data -> data.startsWith(id + ","));

        // Adăugăm noua linie
        buttonData.add(line);

        editor.putStringSet("button_data", buttonData);
        editor.apply();

        Log.d("DEBUG", "Salvăm butonul: id=" + id + ", tag=" + tag + ", text=" + text);
        return line;
    }


    /**
     * Creează meniul contextual
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        getMenuInflater().inflate(R.menu.button_options_menu, menu);
        menu.setHeaderTitle("Button Options");

        // Setăm referința butonului apăsat
        selectedButton = v;
    }


    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (selectedButton instanceof Button) {
            Button button = (Button) selectedButton;

            if (item.getItemId() == R.id.action_delete) {
                buttonContainer.removeView(button);
                removeButtonFromStorage(button.getId());
                selectedButton = null;
                return true;

            } else if (item.getItemId() == R.id.action_transparency) {
                showTransparencyDialog(button);
                return true;

            } else if (item.getItemId() == R.id.action_resize) {
                showResizeDialog(button);
                return true;

            } else if (item.getItemId() == R.id.action_rename) {
                showRenameDialog(button);
                return true;

            } else if (item.getItemId() == R.id.action_set_input) {
                showSetInputDialog(button);
                return true;

            } else if (item.getItemId() == R.id.action_copy) {
                copyButton(button);
                return true;

            } else if (item.getItemId() == R.id.action_slideable) {
                action_slideable(button);
                return true;
            }
        }
        return super.onContextItemSelected(item);
    }

    private void action_slideable(Button button) {
        Boolean isSlideable = (Boolean) button.getTag(R.id.slideable_flag);
        if (isSlideable != null && isSlideable) {
            // Scoatem culoarea
            button.getBackground().clearColorFilter();
            button.setTag(R.id.slideable_flag, false);
        } else {
            // Aplicăm galben deschis
            button.getBackground().setColorFilter(Color.parseColor(SlideColor), android.graphics.PorterDuff.Mode.MULTIPLY);
            button.setTag(R.id.slideable_flag, true);
        }

        saveButtonSettings(button, offset);
    }


    private void copyButton(Button originalButton) {
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
        }

        // Activăm drag & meniul contextual
        enableDrag(copiedButton);
        registerForContextMenu(copiedButton);

        // Adăugăm în UI
        buttonContainer.addView(copiedButton);

        // Salvăm în preferințe
        saveButtonSettings(copiedButton, offset);

        Toast.makeText(this, "✅ Button copied!", Toast.LENGTH_SHORT).show();
    }



    private void removeButtonFromStorage(int buttonId) {
        SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> buttonData = new HashSet<>(prefs.getStringSet("button_data", new HashSet<>()));
        buttonData.removeIf(pos -> pos.startsWith(buttonId + ","));

        editor.putStringSet("button_data", buttonData);
        editor.apply();
    }


    private void showResizeDialog(Button button) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Resize Button");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView widthLabel = new TextView(this);
        widthLabel.setText("Width");
        SeekBar widthSeekBar = new SeekBar(this);
        widthSeekBar.setMax(500);
        widthSeekBar.setMin(50);
        widthSeekBar.setProgress(button.getWidth());

        TextView heightLabel = new TextView(this);
        heightLabel.setText("Height");
        SeekBar heightSeekBar = new SeekBar(this);
        heightSeekBar.setMax(500);
        heightSeekBar.setMin(50);
        heightSeekBar.setProgress(button.getHeight());

        TextView scaleLabel = new TextView(this);
        scaleLabel.setText("Scale Uniformly (Square Size)");
        SeekBar scaleSeekBar = new SeekBar(this);
        scaleSeekBar.setMax(500);
        scaleSeekBar.setMin(50);
        scaleSeekBar.setProgress(Math.max(button.getWidth(), button.getHeight()));

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
            button.setLayoutParams(new FrameLayout.LayoutParams(width, height));
            saveButtonSettings(button);
        });

        builder.setNeutralButton("Scale Uniformly", (dialog, which) -> {
            int scale = scaleSeekBar.getProgress();
            button.setLayoutParams(new FrameLayout.LayoutParams(scale, scale));
            saveButtonSettings(button);
        });

        builder.setNegativeButton("Reset", (dialog, which) -> {
            int resetSize = 100;
            button.setLayoutParams(new FrameLayout.LayoutParams(resetSize, resetSize));
            saveButtonSettings(button);
        });

        widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (lockRatioCheckBox.isChecked()) {
                    float ratio = button.getHeight() / (float) button.getWidth();
                    heightSeekBar.setProgress(Math.round(progress * ratio));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        heightSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (lockRatioCheckBox.isChecked()) {
                    float ratio = button.getWidth() / (float) button.getHeight();
                    widthSeekBar.setProgress(Math.round(progress * ratio));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        builder.show();
    }


    private void showTransparencyDialog(Button button) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Adjust Transparency");

        // Slider pentru transparență
        SeekBar transparencySeekBar = new SeekBar(this);
        transparencySeekBar.setMax(255);
        transparencySeekBar.setMin(5);
        transparencySeekBar.setProgress(button.getBackground().getAlpha());

        builder.setView(transparencySeekBar);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            int alpha = transparencySeekBar.getProgress();
            button.getBackground().setAlpha(alpha);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    private void showRenameDialog(Button button) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Key");

        // Creăm un input pentru a introduce noul nume
        EditText input = new EditText(this);
        input.setText(button.getText().toString()); // Setăm textul actual
        builder.setView(input);

        // Buton "Apply" pentru confirmare
        builder.setPositiveButton("Apply", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                button.setText(newName); // Aplică noul nume pe buton
                //button.setTag("button_name"); // Salvează numele butonului

                // Salvăm și în SharedPreferences
                saveButtonSettings(button);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

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


    private void showLoadPresetDialog(FrameLayout mainContainer, VirtualKeyHandler virtualKeyHandler) {
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


    /**
     * Funcție pentru a extrage preseturile disponibile
     */
    private List<String> getPresetNames(SharedPreferences prefs) {
        Set<String> presetKeys = prefs.getAll().keySet();
        List<String> presetNames = new ArrayList<>();

        for (String key : presetKeys) {
            if (key.startsWith("preset_")) {
                presetNames.add(key.replace("preset_", ""));
            }
        }
        return presetNames;
    }

    /**
     * Afișează opțiuni pentru un preset (Load, Rename, Delete) și actualizează lista în timp real
     */
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

                // 🔄 Actualizează lista în timp real
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


    private void showSetInputDialog(Button button) {
        String[] keys = {
                "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
                "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "Space", "Enter", "Backspace", "Tab", "Escape", "Delete", "Insert",
                "Home", "End", "Page Up", "Page Down",
                "↑", "↓", "←", "→",
                "Alt", "Ctrl", "Shift",
                "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
                "Mouse_Left", "Mouse_Right", "Mouse_Middle", "EMPTY"
        };

        boolean[] checkedItems = new boolean[keys.length];
        List<String> selectedKeys = new ArrayList<>();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Input Keys")
                .setMultiChoiceItems(keys, checkedItems, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        selectedKeys.add(keys[which]);
                    } else {
                        selectedKeys.remove(keys[which]);
                    }
                })
                .setPositiveButton("Apply", (dialog, which) -> {
                    if (!selectedKeys.isEmpty()) {
                        String tagValue = String.join("+", selectedKeys);
                        button.setTag(tagValue);
                        button.setText(tagValue);
                        saveButtonSettings(button, offset);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

                buttonContainer.addView(button);
                buttons.add(button);
            }
        }
        return buttons;
    }



    private void ensureEmptyPresetExists(SharedPreferences prefs) {
        String emptyPresetName = "preset_empty";
        Set<String> defaultPreset = prefs.getStringSet(emptyPresetName, null);

        if (defaultPreset == null) {
            // Dacă presetul „empty” nu există, îl creăm
            SharedPreferences.Editor editor = prefs.edit();

            // Cream un set gol de butoane
            Set<String> emptyPreset = new HashSet<>();

            // Salvăm presetul gol
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
                savePreset(selectedPreset);
            }
            MainActivity instance = MainActivity.getInstance();
            if (instance != null) {
                instance.refreshLoadedPreset(true);
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
