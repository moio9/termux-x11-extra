package com.termux.x11.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.termux.x11.MainActivity;
import com.termux.x11.R;
import com.termux.x11.VirtualKeyMapperActivity;
import com.termux.x11.input.VirtualKeyHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PresetManager {

    public static final int REQUEST_EXPORT_PRESET = 1010;
    public static final int REQUEST_IMPORT_PRESET = 1011;

    public static void exportPreset(Context context, String presetKey) {
        SharedPreferences prefs = context.getSharedPreferences("button_prefs", Context.MODE_PRIVATE);
        Set<String> buttonData = prefs.getStringSet(presetKey, null);

        if (buttonData == null) {
            Toast.makeText(context, "⚠️ Presetul nu există: " + presetKey, Toast.LENGTH_SHORT).show();
            return;
        }

        // Convertim în JSONArray
        JSONArray jsonArray = new JSONArray();
        for (String line : buttonData) {
            jsonArray.put(line);
        }

        // Salvăm într-un fișier
        File dir = new File(Environment.getExternalStorageDirectory(), "VirtualKeyPresets");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, presetKey + ".json");

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(jsonArray.toString(2)); // indentat
            Toast.makeText(context, "✅ Exportat: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(context, "❌ Eroare la export: " + e, Toast.LENGTH_LONG).show();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    public static void importPreset(Context context, String presetKey, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Toast.makeText(context, "❌ Fișierul nu există", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            JSONArray array = new JSONArray(content);
            Set<String> buttonData = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                buttonData.add(array.getString(i));
            }

            SharedPreferences prefs = context.getSharedPreferences("button_prefs", Context.MODE_PRIVATE);
            prefs.edit().putStringSet(presetKey, buttonData).apply();

            Toast.makeText(context, "✅ Importat presetul: " + presetKey, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "❌ Eroare la import: " + e, Toast.LENGTH_LONG).show();
        }
    }


    public static String exportAllPresets(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("button_prefs", Context.MODE_PRIVATE);
        JSONObject result = new JSONObject();

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("preset_") && entry.getValue() instanceof Set) {
                // Convertim fiecare preset într-un JSONArray
                JSONArray array = new JSONArray((Set<?>) entry.getValue());
                try {
                    result.put(entry.getKey(), array);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        return result.toString();
    }

    public static void loadPresetAndAddToUI(Context context, String presetKey, FrameLayout container, VirtualKeyHandler handler) {
        List<Button> buttons = VirtualKeyMapperActivity.loadPreset(context, presetKey, container, 0F);

        for (Button btn : buttons) {
            handler.setupInputForButton(btn, container);
            if (btn.getParent() == null)
                container.addView(btn);
        }

        SharedPreferences prefs = context.getSharedPreferences("button_prefs", Context.MODE_PRIVATE);
        String displayId = VirtualKeyMapperActivity.getDisplayId(context);
        prefs.edit().putString("last_used_preset_" + displayId, presetKey).apply();

        Toast.makeText(context, "✅ Preset loaded: " + presetKey.replace("preset_", ""), Toast.LENGTH_SHORT).show();
    }


}

