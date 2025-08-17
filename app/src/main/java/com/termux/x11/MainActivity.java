package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;
import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;
import static com.termux.x11.VirtualKeyMapperActivity.getDisplayId;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.input.GamepadInputHandler;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.input.VirtualKeyHandler;
import com.termux.x11.ipc.GamepadIpc;
import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    private GamepadIpc ipc;
    private final GamepadIpc.GamepadState gpState = new GamepadIpc.GamepadState().neutral();

    private final GamepadIpc.Listener ipcListener = new GamepadIpc.Listener() {
        @Override public int onGetGamepadRequested() {
            // Lasă ambele să se atașeze dacă vor
            return GamepadIpc.FLAG_INPUT_TYPE_XINPUT | GamepadIpc.FLAG_INPUT_TYPE_DINPUT;
        }
        @Override public void onRumble(int l, int r, int durMs) {
            if (gamepadHandler != null) gamepadHandler.rumble(l, r, durMs);
        }
        @Override public void onRelease() {
            if (gamepadHandler != null) gamepadHandler.cancelRumble();
        }
        @Override public void onLog(String msg) { android.util.Log.d("GamepadIPC", msg); }
    };


    public GamepadIpc getGamepadIpc() { return ipc; }
    public GamepadIpc.GamepadState getGamepadState() { return gpState; }

    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    public static Handler handler = new Handler();
    FrameLayout frm;
    private TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private Notification mNotification;
    private final int mNotificationId = 7892;
    NotificationManager mNotificationManager;
    static InputMethodManager inputMethodManager;
    private static boolean showIMEWhileExternalConnected = true;
    private static boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;

    public static Prefs prefs = null;
    private static int parseIntOr(String s, int def){ try { return Integer.parseInt(s.trim()); } catch(Exception e){ return def; } }
    private static int clamp(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }
    private static int clampPort(String s, int def){ return clamp(parseIntOr(s, def), 1, 65535); }
    private static int clampId(String s, int def){ return clamp(parseIntOr(s, def), 0, 0x7fffffff); }
    private String  currHost;
    private int     currClientPort, currServerPort, currGpId, currStateHz;
    private boolean currForwardX11;
    private String  currInputType, currMode, currVibrateMode;
    private int     currVibrateStrength;
    private boolean isBooting = true;
    private String currGpName = "Termux-X11 Pad";


    private static boolean oldFullscreen = false, oldHideCutout = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs.recheckStoringSecondaryDisplayPreferences();
            if (ACTION_START.equals(intent.getAction())) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                    onReceiveConnection(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (LorieView.connected())
                handler.post(() -> findViewById(android.R.id.content).getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener));
            return false;
        }
    };

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;

    public MainActivity() {
        instance = this;
    }

    public static Prefs getPrefs() {
        return prefs;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    private VirtualKeyHandler virtualKeyHandler;
    private GamepadInputHandler gamepadHandler;


    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);

        getWindow().setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);


        frm = findViewById(R.id.frame);
        findViewById(R.id.command_button).setOnClickListener((l) -> {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(MainActivity.this, "Termux is not installed.", Toast.LENGTH_LONG).show();
            }
            try {
                Thread.sleep(1500); // Wait for app to launch
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bootx");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{});
//            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            try {
                getApplicationContext().startService(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{
            setAction(Intent.ACTION_MAIN);
        }}));
        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());
        findViewById(R.id.support_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/moio9/termux-x11-extra"))));

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
        mLorieKeyListener = (v, k, e) -> {
            final InputDevice dev = e.getDevice();
            final int src = (dev != null) ? dev.getSources() : 0;

            final boolean hasKb   = (src & InputDevice.SOURCE_KEYBOARD) != 0;
            final boolean hasGp   = (src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
            final boolean hasDpad = (src & InputDevice.SOURCE_DPAD) != 0;

            // chei „clar” de gamepad (A/B/X/Y/L1/R1/etc)
            final boolean isGpBtn = KeyEvent.isGamepadButton(k);

            // DPAD: tratează ca gamepad doar dacă vine de pe un device care NU e și tastatură
            // (multe controllere au GAMEPAD/JOYSTICK/DPAD dar NU KEYBOARD; tastaturile reale au KEYBOARD)
            final boolean isDpadKey =
                    (k == KeyEvent.KEYCODE_DPAD_UP
                            || k == KeyEvent.KEYCODE_DPAD_RIGHT
                            || k == KeyEvent.KEYCODE_DPAD_DOWN
                            || k == KeyEvent.KEYCODE_DPAD_LEFT
                            || k == KeyEvent.KEYCODE_DPAD_CENTER);

            final boolean fromController =
                    (isGpBtn && (hasGp || hasDpad)) ||
                            (isDpadKey && !hasKb && (hasGp || hasDpad));

            if (fromController && gamepadHandler != null) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    gamepadHandler.handleKeyDown(k, e);
                    return true;
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    gamepadHandler.handleKeyUp(k, e);
                    return true;
                } else {
                    return true;
                }
            }

            // altfel e tastatură (inclusiv săgeți de la tastatură reală) -> lasă pe fluxul normal
            boolean result = mInputHandler.sendKeyEvent(e);

            // nu „șterge” special keys decât pentru intrări de la tastatură
            if (useTermuxEKBarBehaviour && mExtraKeys != null && hasKb)
                mExtraKeys.unsetSpecialKeys();

            return result;
        };
        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((surfaceWidth, surfaceHeight, screenWidth, screenHeight) -> {
            String name;
            int framerate = (int) ((lorieView.getDisplay() != null) ? lorieView.getDisplay().getRefreshRate() : 30);

            mInputHandler.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            mInputHandler.handleClientSizeChanged(screenWidth, screenHeight);
            if (lorieView.getDisplay() == null || lorieView.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY)
                name = "builtin";
            else if (SamsungDexUtils.checkDeXEnabled(this))
                name = "dex";
            else
                name = "external";
            LorieView.sendWindowChange(screenWidth, screenHeight, framerate, name);
        });

        registerReceiver(receiver, new IntentFilter(ACTION_START) {{
            addAction(ACTION_PREFERENCES_CHANGED);
            addAction(ACTION_STOP);
            addAction(ACTION_CUSTOM);
        }}, SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
        FullscreenWorkaround.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(() -> content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener), 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }


        if (SDK_INT >= VERSION_CODES.M
                && checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "Please grant permission 'Run commands in Termux environment'", Toast.LENGTH_LONG).show();
        }


        onReceiveConnection(getIntent());
        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());

        FrameLayout mainContainer = findViewById(R.id.frame);
        if (mainContainer == null) {
            return;
        }

        readConfigFromPrefs();
        startIpcFromCurrentConfig(lorieView);
        refreshLoadedPreset(true);
        isBooting = false;
    }

    private void readConfigFromPrefs() {
        String host = prefs != null && prefs.gamepadHost.get() != null ? prefs.gamepadHost.get() : "127.0.0.1";
        currHost = host.isEmpty() ? "127.0.0.1" : host;

        currClientPort      = clampPort(prefs != null ? prefs.gamepadPortRumble.get() : "4600", 4600); // DLL -> Android (GET/RUMBLE)
        currGpId            = clampId  (prefs != null ? prefs.gamepadID.get()        : "1", 1);
        currStateHz         = Math.max(10, Math.min(500, prefs != null ? prefs.gamepadStateHz.get() : 125));
        currForwardX11      = (prefs != null) && prefs.gamepadForwardX11.get();
        currInputType       = (prefs != null && prefs.gamepadInputType.get()!=null) ? prefs.gamepadInputType.get() : "xinput";
        currMode            = (prefs != null && prefs.gamepadMode.get()!=null)       ? prefs.gamepadMode.get()      : "mapped";
        currVibrateMode     = (prefs != null && prefs.gamepadVibrate.get()!=null)    ? prefs.gamepadVibrate.get()   : "system";
        currVibrateStrength = (prefs != null) ? prefs.gamepadVibrateStrength.get() : 128;
        currGpName          = (prefs != null) ? prefs.gamepadName.get() : "Termux-X11 Pad";
    }

    private void maybeReloadGamepad(LorieView lorieView) {
        if (lorieView == null) return;
        if (prefs == null) return;

        String  oldHost = currHost;
        int     oldCli  = currClientPort, oldSrv = currServerPort, oldId = currGpId, oldHz = currStateHz;
        String  oldIn   = currInputType, oldMode = currMode;
        boolean oldFwd  = currForwardX11;
        String  oldVibMode = currVibrateMode;
        int     oldVibStr  = currVibrateStrength;

        readConfigFromPrefs();

        boolean needIpcRestart =
                !currHost.equals(oldHost) ||
                        currClientPort != oldCli ||
                        currServerPort != oldSrv ||
                        currGpId       != oldId ||
                        currStateHz    != oldHz ||
                        !currInputType.equals(oldIn) ||
                        !currMode.equals(oldMode);

        if (needIpcRestart) {
            startIpcFromCurrentConfig(lorieView);
            refreshLoadedPreset(true);
        } else {
            if (ipc != null) ipc.setPumpHz(currStateHz);
            if (gamepadHandler != null) gamepadHandler.reloadPrefs(prefs);
        }

        if (gamepadHandler != null && (oldFwd != currForwardX11 ||
                !oldVibMode.equals(currVibrateMode) || oldVibStr != currVibrateStrength)) {
            gamepadHandler.reloadPrefs(prefs);
        }

    }

    private void startIpcFromCurrentConfig(LorieView lorieView) {
        String mode = prefs.gamepadInputType.get(); // "all" / "xinput" / "dinput" / "none"
        String host = prefs.gamepadHost.get();      // "127.0.0.1"
        int base    = Integer.parseInt(prefs.gamepadPortRumble.get()); // ex 4600
        int gpId    = Integer.parseInt(prefs.gamepadID.get());         // ex 1
        int stateHz = prefs.gamepadStateHz.get();

        GamepadIpc.HandshakeFormat fmt;
        switch ((prefs.gamepadInputType.get()+"").toLowerCase()) {
            case "xinput": fmt = GamepadIpc.HandshakeFormat.NEW;    break;   // doar XInput
            case "dinput": fmt = GamepadIpc.HandshakeFormat.LEGACY; break;   // doar DInput (legacy)
            case "all":    fmt = GamepadIpc.HandshakeFormat.BOTH;   break;   // auto: răspunde în formatul cerut
            case "none":   fmt = GamepadIpc.HandshakeFormat.NONE;   break;   // nu răspunde deloc
            default:       fmt = GamepadIpc.HandshakeFormat.BOTH;   break;
        }

        // oprește instanța veche
        try { if (ipc != null) ipc.sendRelease(); } catch (Throwable ignored) {}
        try { if (ipc != null) ipc.stop(); } catch (Throwable ignored) {}

        ipc = new GamepadIpc(
                host,
                /* clientPort = */ base,         // Android ascultă aici
                /* serverPort = */ base + 1,     // opțional/fallback
                gpId,
                new GamepadIpc.Listener() {
                    @Override public int onGetGamepadRequested() {
                        switch (mode) {
                            case "xinput":
                                return GamepadIpc.FLAG_INPUT_TYPE_XINPUT;
                            case "dinput":
                                // DInput, cu mapare XInput ca să ai butoanele la locul lor
                                return GamepadIpc.FLAG_INPUT_TYPE_DINPUT
                                        | GamepadIpc.FLAG_DINPUT_MAPPER_XINPUT;
                            case "all":
                            default:
                                // Trimite ambele: XInput + DInput (mapper XInput)
                                return GamepadIpc.FLAG_INPUT_TYPE_XINPUT
                                        | GamepadIpc.FLAG_INPUT_TYPE_DINPUT
                                        | GamepadIpc.FLAG_DINPUT_MAPPER_XINPUT;
                        }
                    }
                    @Override public void onRumble(int l, int r, int durMs) {
                        if (gamepadHandler != null) gamepadHandler.rumble(l, r, durMs);
                    }
                    @Override public void onRelease() {
                        if (gamepadHandler != null) gamepadHandler.cancelRumble();
                    }
                    @Override public void onLog(String msg) { android.util.Log.d("GamepadIPC", msg); }
                },
                fmt
        );
        ipc.setPumpHz(stateHz);
        ipc.setName(currGpName);
        ipc.start();

        // inițializează handler-ul de gamepad
        gamepadHandler = new GamepadInputHandler(this, lorieView, ipc, gpState, prefs.gamepadForwardX11.get());
        gamepadHandler.reloadPrefs(prefs);
        gamepadHandler.setupGamepadInput();
    }

    private void stopIpc() {
        try { if (gamepadHandler != null) gamepadHandler.cancelRumble(); } catch (Throwable ignored) {}
        try { if (ipc != null) ipc.sendRelease(); } catch (Throwable ignored) {}
        try { if (ipc != null) ipc.stop(); } catch (Throwable ignored) {}
        ipc = null; gamepadHandler = null;
    }

    private static int safeParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        final int src = event.getSource();
        final boolean fromGamepad =
                (src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)) != 0;

        if (fromGamepad) {
            return false;
        }
        return super.onKeyDown(keyCode, event);
    }


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        final int src = event.getSource();
        final boolean fromGamepad =
                (src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)) != 0;

        if (fromGamepad) {
            return false;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Lasă sistemul să livreze evenimentul normal (IME, views etc).
        // Noi prindem în onKeyDown/onKeyUp mai jos.
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepadHandler != null) {
            return gamepadHandler.handleGenericMotionEvent(event) || super.onGenericMotionEvent(event);
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isPresetLoaded = false;
    public void refreshLoadedPreset(boolean forceLoad) {
        if (!isPresetLoaded || forceLoad){
            FrameLayout buttonLayer = findViewById(R.id.top);

            List<View> toRemove = new ArrayList<>();
            for (int i = 0; i < buttonLayer.getChildCount(); i++) {
                View child = buttonLayer.getChildAt(i);
                if (child instanceof Button) {
                    toRemove.add(child);
                }
            }
            for (View view : toRemove) {
                buttonLayer.removeView(view);
            }
            if (LorieView.connected()){
                SharedPreferences prefs = getSharedPreferences("button_prefs", MODE_PRIVATE);
                String screenID = getDisplayId(this);
                String lastPreset = prefs.getString("last_used_preset_" + screenID, "preset_empty");

                VirtualKeyHandler virtualKeyHandler = new VirtualKeyHandler(this, getLorieView(), ipc, gpState);
                VirtualKeyMapperActivity virtualKeyMapperActivity = new VirtualKeyMapperActivity();
                List<Button> buttons = virtualKeyMapperActivity.loadPreset(this, lastPreset, buttonLayer);

                for (Button btn : buttons) {
                    virtualKeyHandler.setupInputForButton(btn, buttonLayer);

                    if (btn.getParent() == null) {
                        buttonLayer.addView(btn);
                    }
                }

            }
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        super.onDestroy();
        try { if (ipc != null) { ipc.sendRelease(); ipc.stop(); } } catch (Throwable ignored) {}
    }


    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && LorieView.connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (LorieView.connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        int maxYDecrement = (pager.getVisibility() == View.VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((LorieView.connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frm.getX(), maxX));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frm.getY(), maxY));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
                    switch(e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_POINTER_DOWN:
                            getLorieView().sendMouseEvent(0, 0, b, true, true);
                            v.setPressed(true);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_POINTER_UP:
                            getLorieView().sendMouseEvent(0, 0, b, false, true);
                            v.setPressed(false);
                            break;
                    }
                    return true;
                }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationInWindow(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final ViewPager pager = getTerminalToolbarViewPager();
                        int[] offset = new int[2];
                        primaryLayer.getLocationInWindow(offset);
                        float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                        float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                        if (pager.getVisibility() == View.VISIBLE)
                            maxY -= pager.getHeight();

                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), frm.getX(), maxX));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), frm.getY(), maxY));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationInWindow(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    }

    void onReceiveConnection(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder ibinder = bundle == null ? null : bundle.getBinder(null);
        if (ibinder == null)
            return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> { LorieView.connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (LorieView.connected())
            return false;

        if (service == null) {
            boolean sent = LorieView.requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            handler.postDelayed(this::tryConnect, 250);
        }
        return false;
    }

    void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key)) return;

        if (isBooting) return; // <- important
        LorieView lv = getLorieView();
        if (lv == null) return; // protecție

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
        // mută reload-ul gamepad aici, după debounce-ul grafic:
        handler.post(() -> maybeReloadGamepad(lv));
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs.recheckStoringSecondaryDisplayPreferences();

        onWindowFocusChanged(hasWindowFocus());
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && LorieView.connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);

        lorieView.requestLayout();
        lorieView.invalidate();

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId) {
                mNotification = buildNotification();
                mNotificationManager.notify(mNotificationId, mNotification);
            }
    }

    @Override
    public void onResume() {
        super.onResume();

        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId)
                mNotificationManager.cancel(mNotificationId);

        super.onPause();
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = LorieView.connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e)));
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        frm.setPadding(0, 0, 0, prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0);
        getLorieView().requestFocus();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && LorieView.connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
        getWindow().setSoftInputMode(prefs.Reseed.get() ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        NotificationCompat.Builder builder =  new NotificationCompat.Builder(this, getNotificationChannel(mNotificationManager))
                .setContentTitle("Termux:X11-Extra")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }

    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);
        String channelName = getResources().getString(R.string.app_name);
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (SDK_INT >= VERSION_CODES.Q)
            channel.setAllowBubbles(false);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

    int orientation;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        orientation = newConfig.orientation;
        setTerminalToolbarView();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();
        prefs.recheckStoringSecondaryDisplayPreferences();
        Window window = getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();
        boolean reseed = prefs.Reseed.get();

        if (oldHideCutout != hideCutout || oldFullscreen != fullscreen) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (hasFocus) {
            if (SDK_INT >= VERSION_CODES.P) {
                if (hideCutout)
                    getWindow().getAttributes().layoutInDisplayCutoutMode = (SDK_INT >= VERSION_CODES.R) ?
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS :
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                else
                    getWindow().getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }

            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }

        window.setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        if (hasFocus) {
            if (fullscreen) {
                window.addFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                window.clearFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(0);
            }
        }

        if (prefs.keepScreenOn.get())
            window.addFlags(FLAG_KEEP_SCREEN_ON);
        else
            window.clearFlags(FLAG_KEEP_SCREEN_ON);

        window.setSoftInputMode(reseed ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);

        ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0).setFitsSystemWindows(!fullscreen);
    }

    @Override
    public void onBackPressed() {
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onUserLeaveHint() {
        if (prefs.PIP.get() && hasPipPermission(this)) {
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        Log.d("MainActivity", "Toggling keyboard visibility");
        if(inputMethodManager != null) {
            android.util.Log.d("toggleKeyboardVisibility", "externalKeyboardConnected " + externalKeyboardConnected + " showIMEWhileExternalConnected " + showIMEWhileExternalConnected);
            if (!externalKeyboardConnected || showIMEWhileExternalConnected)
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            else
                inputMethodManager.hideSoftInputFromWindow(getInstance().getWindow().getDecorView().getRootView().getWindowToken(), 0);

            getInstance().getLorieView().requestFocus();
        }
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = LorieView.connected();
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected ? View.INVISIBLE : View.VISIBLE);
            getLorieView().setVisibility(connected ? View.VISIBLE : View.INVISIBLE);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected) {
                tryConnect();
                isPresetLoaded = false;
                refreshLoadedPreset(false);
            } else{
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
                refreshLoadedPreset(false);
                isPresetLoaded = true;
            }
            onWindowFocusChanged(hasWindowFocus());
        });
    }

    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return LorieView.connected();
    }

    public static void getRealMetrics(DisplayMetrics m) {
        if (getInstance() != null &&
                getInstance().getLorieView() != null &&
                getInstance().getLorieView().getDisplay() != null)
            getInstance().getLorieView().getDisplay().getRealMetrics(m);
    }

    public static void setCapturingEnabled(boolean enabled) {
        if (getInstance() == null || getInstance().mInputHandler == null)
            return;

        getInstance().mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
        getLorieView().requestFocus();
    }
}