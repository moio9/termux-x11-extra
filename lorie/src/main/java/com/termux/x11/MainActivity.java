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
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.input.GamepadInputHandler;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.input.VirtualKeyHandler;
import com.termux.x11.ipc.GamepadIpc;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    private GamepadIpc ipc;
    private final GamepadIpc.GamepadState gpState = new GamepadIpc.GamepadState().neutral();

    private final GamepadIpc.Listener ipcListener = new GamepadIpc.Listener() {
        @Override public int onGetGamepadRequested() {
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

    public GamepadInputHandler getGamepadHandler() { return gamepadHandler; }

    /** Called by the LORIE-CONTROLLER X11 extension through the private UI channel. */
    public void controllerRumble(int effect, int low, int high, int durationMs) {
        if (gamepadHandler == null) return;
        if ((low == 0 && high == 0) || durationMs <= 0)
            gamepadHandler.cancelRumble();
        else
            gamepadHandler.rumble(low, high, durationMs);
    }

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
    private static DisplayManager displayManager;
    private static boolean showIMEWhileExternalConnected = true;
    private static boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;
    /** The display the system letterboxed us on instead of rotating, {@code null} until it does. */
    private Rect orientationDeniedAt = null;
    private String screenIdleTimeoutArmedMode = null; // numeric screenIdleTimeout mode the pending idle check reflects, or null if none pending
    private final Runnable screenIdleTimeoutCheck = this::checkScreenIdleTimeout;
    /** Aspect ratios outside of the range the device is configured with are rejected by the system. */
    private static final float MIN_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMinAspectRatio", 1.f / 2.39f);
    private static final float MAX_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMaxAspectRatio", 2.39f);

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
    private OrientationEventListener orientationListener;

    public void onBroadcastReceive(Context context, Intent intent) {
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

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!getLorieView().connected())
                return false;

            finishStartupDraw();
            return true;
        }
    };

    private void finishStartupDraw() {
        View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener);
        content.invalidate();
    }

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

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);
        applyWindowSettings();


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

            final boolean isGpBtn = KeyEvent.isGamepadButton(k);

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

            boolean result = mInputHandler.sendKeyEvent(e);

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
        if (SDK_INT >= VERSION_CODES.O) {
            lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
            lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        }
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((screenWidth, screenHeight, inputTransform) ->
                mInputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                setTerminalToolbarViewLayout();
            }
        };
        frm.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            String savedPos;
            int savedRotation;

            @Override
            public void onGlobalLayout() {
                Display d = frm.getDisplay();
                String pos = prefs.ekbarPosition.get();
                if ((d != null && savedRotation != d.getRotation()) || !Objects.equals(savedPos, pos)) {
                    savedRotation = d == null ? 0 : d.getRotation();
                    savedPos = pos;
                    setTerminalToolbarViewLayout();
                }
            }
        });

        ImeHeightProvider.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
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

        onPreferencesChangedCallback();
        maybeReloadGamepad(getLorieView());
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
        String mode = (prefs.gamepadInputType.get() + "").toLowerCase();
        // "all" / "xinput" / "dinput" / "none", plus display-label aliases
        String host = prefs.gamepadHost.get();      // "127.0.0.1"
        int base    = Integer.parseInt(prefs.gamepadPortRumble.get()); // ex 4600
        int gpId    = Integer.parseInt(prefs.gamepadID.get());         // ex 1
        int stateHz = prefs.gamepadStateHz.get();

        GamepadIpc.HandshakeFormat fmt;
        switch ((prefs.gamepadInputType.get()+"").toLowerCase()) {
            case "xinput": fmt = GamepadIpc.HandshakeFormat.NEW;    break;
            case "dinput":
            case "directinput": fmt = GamepadIpc.HandshakeFormat.LEGACY; break;
            case "all":
            case "xdinput": fmt = GamepadIpc.HandshakeFormat.BOTH;   break;
            case "none":   fmt = GamepadIpc.HandshakeFormat.NONE;   break;
            default:       fmt = GamepadIpc.HandshakeFormat.BOTH;   break;
        }

        try { if (ipc != null) ipc.sendRelease(); } catch (Throwable ignored) {}
        try { if (ipc != null) ipc.stop(); } catch (Throwable ignored) {}

        ipc = new GamepadIpc(
                host,
                /* clientPort = */ base,
                /* serverPort = */ base + 1,
                gpId,
                new GamepadIpc.Listener() {
                    @Override public int onGetGamepadRequested() {
                        switch (mode) {
                            case "xinput":
                                return GamepadIpc.FLAG_INPUT_TYPE_XINPUT;
                            case "dinput":
                            case "directinput":
                                return GamepadIpc.FLAG_INPUT_TYPE_DINPUT
                                        | GamepadIpc.FLAG_DINPUT_MAPPER_XINPUT;
                            case "all":
                            default:
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

        if (gamepadHandler != null) gamepadHandler.shutdown();
        gamepadHandler = new GamepadInputHandler(this, lorieView, ipc, gpState, prefs.gamepadForwardX11.get());
        gamepadHandler.reloadPrefs(prefs);
        gamepadHandler.setupGamepadInput();
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
        /*
         * Controller key-up events do not reliably pass through
         * LorieView.dispatchKeyEventPreIme(). Handle the complete controller
         * key stream here, before the focused view/IME gets a chance to split
         * the down and up events across different dispatch paths.
         */
        final InputDevice dev = e.getDevice();
        final int src = (dev != null) ? dev.getSources() : e.getSource();
        final boolean hasKb = (src & InputDevice.SOURCE_KEYBOARD) != 0;
        final boolean hasGp =
                (src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
        final boolean hasDpad = (src & InputDevice.SOURCE_DPAD) != 0;
        final int keyCode = e.getKeyCode();
        final boolean isDpadKey =
                keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        final boolean fromController =
                (KeyEvent.isGamepadButton(keyCode) && (hasGp || hasDpad))
                        || (isDpadKey && !hasKb && (hasGp || hasDpad));

        if (fromController && gamepadHandler != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0)
                    gamepadHandler.handleKeyDown(keyCode, e);
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                gamepadHandler.handleKeyUp(keyCode, e);
                return true;
            }
        }
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
            if (getLorieView().connected()) {
                SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_BUTTON_PREFS, MODE_PRIVATE);
                String screenID = getDisplayId(this);
                String lastPreset = prefs.getString(AppConstants.PREFS_LAST_USED_PRESET_PREFIX + screenID, AppConstants.PRESET_EMPTY);

                virtualKeyHandler = new VirtualKeyHandler(this, getLorieView(), ipc, gpState, gamepadHandler);
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
        handler.removeCallbacks(screenIdleTimeoutCheck);
        if (instance == this)
            instance = null;
        super.onDestroy();
        try { if (gamepadHandler != null) gamepadHandler.shutdown(); } catch (Throwable ignored) {}
        try { if (ipc != null) { ipc.sendRelease(); ipc.stop(); } } catch (Throwable ignored) {}
    }


    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && getLorieView().connected();
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
        if (SDK_INT >= VERSION_CODES.O)
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
                RectF frmRect = getVisibleFrmRect();
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frmRect.right - 4 * left.getWidth();
                float maxY = frmRect.bottom - 4 * left.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, maxY));

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
                RectF frmRect = getVisibleFrmRect();

                //Calculate screen border making sure btn is fully inside the view
                float minX = frmRect.left, minY = frmRect.top;
                float maxX = frmRect.right;
                float maxY = frmRect.bottom;

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() + visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(dY, frmRect.top, frmRect.bottom));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, frmRect.bottom));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (getLorieView().connected() && show) {
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

    private RectF getVisibleFrmRect() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Rect frmRect = new Rect();
        frm.getGlobalVisibleRect(frmRect);
        RectF result = new RectF(frmRect.left, frmRect.top, frmRect.right, frmRect.bottom);
        if (pager.getVisibility() == View.VISIBLE) {
            // getGlobalVisibleRect ignores setRotation(), so we compute bounds from bar thickness
            // directly. For LEFT/RIGHT the pager is rotated 90°: its measured height is always
            // the on-screen thin dimension regardless of orientation.
            int barThickness = pager.getMeasuredHeight();
            switch (getPagerPosition()) {
                case PAGER_POSITION_TOP:    result.top    += barThickness; break;
                case PAGER_POSITION_BOTTOM: result.bottom -= barThickness; break;
                case PAGER_POSITION_LEFT:   result.left   += barThickness; break;
                case PAGER_POSITION_RIGHT:  result.right  -= barThickness; break;
            }
        }
        return result;
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final RectF frmRect = getVisibleFrmRect();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frmRect.left, frmRect.right - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frmRect.top, frmRect.bottom - mouseAuxButtons.getHeight()));
        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frmRect.left, frmRect.right - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frmRect.top, frmRect.bottom - stylusAuxButtons.getHeight()));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((getLorieView().connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
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
                final RectF frmRect = getVisibleFrmRect();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frmRect.left, frmRect.right - primaryLayer.getWidth()));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
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
                        final RectF frmRect = getVisibleFrmRect();
                        final ViewPager pager = getTerminalToolbarViewPager();
                        int[] offset = new int[2];
                        primaryLayer.getLocationInWindow(offset);
                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), frmRect.left, frmRect.right - primaryLayer.getWidth()));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
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
                runOnUiThread(() -> { getLorieView().connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (getLorieView().connected())
            return false;

        if (service == null) {
            boolean sent = getLorieView().requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                getLorieView().connect(fd.detachFd());
                finishStartupDraw();
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

        if (isBooting) return;
        LorieView lv = getLorieView();
        if (lv == null) return;

        /* The command-line preference helper writes through the companion
         * provider, so refresh the display-specific backing store before the
         * gamepad reload reads it.  The delayed UI refresh below is too late
         * and otherwise makes runtime profile changes lag one event behind. */
        prefs.recheckStoringSecondaryDisplayPreferences();

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
        handler.post(() -> maybeReloadGamepad(lv));
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs.recheckStoringSecondaryDisplayPreferences();

        // There is no way back to the normal size from picture-in-picture, so the window is closed.
        if (isInPictureInPictureMode && !prefs.PIP.get()) {
            finish();
            return;
        }

        applyWindowSettings();
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        if (mExtraKeys != null)
            mExtraKeys.reload();
        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && getLorieView().connected() ? View.VISIBLE : View.GONE);
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

        orientationListener.enable();
        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        getLorieView().setKeyboardVisible(false);

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId)
                mNotificationManager.cancel(mNotificationId);

        orientationListener.disable();
        super.onPause();
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    // We can not define function-static variables in Java, so we are defining them outside a function
    private final X11ToolbarViewPager.PageAdapter mPageAdapter =
            new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e));
    private final X11ToolbarViewPager.OnPageChangeListener mOnPageListener = new X11ToolbarViewPager.OnPageChangeListener(this);
    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = !isInPictureInPictureMode && getLorieView().connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            if (pager.getAdapter() != mPageAdapter)
                pager.setAdapter(mPageAdapter);
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(mOnPageListener);
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        setTerminalToolbarViewLayout();
        getLorieView().requestFocus();
    }

    // Keep in sync with Surface.ROTATION_*
    public static final int PAGER_POSITION_TOP = 0, PAGER_POSITION_LEFT = 1, PAGER_POSITION_BOTTOM = 2, PAGER_POSITION_RIGHT = 3;
    public int getPagerPosition() {
        String _pos = prefs.ekbarPosition.get();
        int pos = "top".equals(_pos) ? PAGER_POSITION_TOP : "left".equals(_pos) ? PAGER_POSITION_LEFT : "bottom".equals(_pos) ? PAGER_POSITION_BOTTOM : "right".equals(_pos) ? PAGER_POSITION_RIGHT : 0;
        if (prefs.ekbarPositionIgnoreOrientation.get()) {
            Display dpy = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (dpy != null)
                pos = (pos + dpy.getRotation()) % 4;
        }
        return pos;
    }

    @SuppressLint("RtlHardcoded")
    private void setTerminalToolbarViewLayout() {
        handler.post(() -> {
            final ViewPager pager = getTerminalToolbarViewPager();
            boolean showNow = pager.getVisibility() == View.VISIBLE;
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) pager.getLayoutParams();
            int pos = getPagerPosition();

            // The window is not resized for the keyboard, so a bar along a side has to end above it.
            layoutParams.width = (pos == PAGER_POSITION_LEFT || pos == PAGER_POSITION_RIGHT) ? frm.getHeight() - imeHeight : frm.getWidth();
            layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                    (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));

            switch (pos) {
                case PAGER_POSITION_TOP:
                case PAGER_POSITION_BOTTOM:
                    layoutParams.gravity = (pos == PAGER_POSITION_TOP ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT;
                    // reset everything we set for "left" and "right"
                    pager.setPivotX(layoutParams.width / 2f);
                    pager.setPivotY(layoutParams.width / 2f);
                    pager.setRotation(0f);
                    pager.setTranslationX(0);
                    break;
                case PAGER_POSITION_LEFT:
                case PAGER_POSITION_RIGHT:
                    layoutParams.gravity = (pos == PAGER_POSITION_LEFT ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
                    pager.setPivotX(pos == PAGER_POSITION_LEFT ? 0 : layoutParams.width);
                    pager.setPivotY(0f);
                    pager.setRotation(90f * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    pager.setTranslationX(layoutParams.height * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    break;
            }
            pager.setLayoutParams(layoutParams);

            ekbarContentInset = prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0;
            applyContentInsets();
        });
    }

    private int ekbarContentInset = 0;
    private int imeHeight = 0;
    private int captionHeight = 0;

    private void applyContentInsets() {
        int imeContentInset = prefs.Reseed.get() ? imeHeight : 0;
        int pos = getPagerPosition();
        getLorieView().setContentInsets(pos == PAGER_POSITION_LEFT ? ekbarContentInset : 0,
                captionHeight + (pos == PAGER_POSITION_TOP ? ekbarContentInset : 0),
                pos == PAGER_POSITION_RIGHT ? ekbarContentInset : 0,
                imeContentInset + (pos == PAGER_POSITION_BOTTOM ? ekbarContentInset : 0));
        getLorieView().setObscuredBottom(imeHeight - imeContentInset);

        // Only a bar at the bottom has to step aside for the keyboard.
        int bottomMargin = pos == PAGER_POSITION_BOTTOM ? imeHeight : 0;
        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.MarginLayoutParams pagerParams = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
        if (pagerParams.bottomMargin != bottomMargin) {
            pagerParams.bottomMargin = bottomMargin;
            pager.setLayoutParams(pagerParams);
        }
    }

    public void setImeHeight(int height) {
        // Reported on every insets dispatch, but relaying out the bar for it must not become a loop.
        if (imeHeight == height)
            return;

        imeHeight = height;
        setTerminalToolbarViewLayout();
    }

    // The window header of desktop windowing can not be hidden, so its space has to be given up even
    // in fullscreen mode, where fitsSystemWindows does not apply system insets.
    public void setCaptionHeight(int height) {
        captionHeight = height;
        applyContentInsets();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && getLorieView().connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
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
                .setContentText(getResources().getText(R.string.lorie_notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }

    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.lorie_app_name);
        if (SDK_INT >= VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH);
            channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            if (SDK_INT >= VERSION_CODES.Q)
                channel.setAllowBubbles(false);
            notificationManager.createNotificationChannel(channel);
        }
        return channelId;
    }

    int orientation, densityDpi;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            getLorieView().setKeyboardVisible(false);

        if (newConfig.densityDpi != densityDpi)
            orientationDeniedAt = null;

        orientation = newConfig.orientation;
        densityDpi = newConfig.densityDpi;
        applyWindowSettings();
        setTerminalToolbarView();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();

        // The system bars come back when the window loses focus.
        if (hasFocus) {
            applyImmersiveMode();
            LorieView.markUserActivity();
            applyScreenIdleTimeout();
        }
    }

    private void applyImmersiveMode() {
        boolean isFullscreen = prefs.fullscreen.get();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(!isFullscreen);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (!isFullscreen)
                    controller.show(WindowInsets.Type.systemBars());
                else {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } else
            getWindow().getDecorView().setSystemUiVisibility(!isFullscreen ? 0 :
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setWindowFlag(int flag, boolean enabled) {
        if (((getWindow().getAttributes().flags & flag) != 0) == enabled)
            return;

        if (enabled)
            getWindow().addFlags(flag);
        else
            getWindow().clearFlags(flag);
    }

    /** Keeps or drops the screen-on flag based on elapsed idle time, rescheduling itself for the remaining time. */
    private void checkScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        if ("never".equals(mode) || "system".equals(mode)) {
            screenIdleTimeoutArmedMode = null;
            return;
        }

        long systemTimeoutMs = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        long timeoutMs = Math.max(Long.parseLong(mode) * 60_000L - systemTimeoutMs, 0);
        long elapsed = (System.nanoTime() / 1_000_000L) - LorieView.getLastInputTimestamp();
        if (elapsed >= timeoutMs) {
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, false);
        } else {
            screenIdleTimeoutArmedMode = mode;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, true);
            handler.postDelayed(screenIdleTimeoutCheck, timeoutMs - elapsed);
        }
    }

    /** Syncs screenIdleTimeout state/timer to the current preference without extending an already-scheduled check. */
    private void applyScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        boolean connected = getLorieView().connected();
        if (!connected || "never".equals(mode) || "system".equals(mode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, connected && "never".equals(mode));
        } else if (!mode.equals(screenIdleTimeoutArmedMode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    void applyWindowSettings() {
        Window window = getWindow();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();

        // Recreating would take the window out of picture-in-picture, so it waits for the normal size.
        if (!isInPictureInPictureMode && (oldHideCutout != hideCutout || oldFullscreen != fullscreen)) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (isInMultiWindowMode() ? "auto" : prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        // A display ignoring orientation requests letterboxes the window into the requested
        // proportions instead of rotating, leaving the rest of the screen unusable. The request is
        // retried once the display changes, the next one may well honour it.
        if (SDK_INT >= VERSION_CODES.R) {
            WindowManager wm = getWindowManager();
            Rect display = wm.getMaximumWindowMetrics().getBounds();
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !wm.getCurrentWindowMetrics().getBounds().equals(display))
                orientationDeniedAt = display;
            if (display.equals(orientationDeniedAt))
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (SDK_INT >= VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutoutMode = !hideCutout ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER :
                    (SDK_INT >= VERSION_CODES.R ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
            if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                attributes.layoutInDisplayCutoutMode = cutoutMode;
                window.setAttributes(attributes);
            }
        }

        setWindowFlag(FLAG_FULLSCREEN, fullscreen);
        applyScreenIdleTimeout();
        applyImmersiveMode();

        View contentChild = ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0);
        if (contentChild.getFitsSystemWindows() == fullscreen) {
            contentChild.setFitsSystemWindows(!fullscreen);
            ViewCompat.requestApplyInsets(contentChild);
        }
    }

    @Override
    public void onBackPressed() {
    }

    private static float getSystemDimenFloat(String name, float fallback) {
        Resources resources = Resources.getSystem();
        TypedValue value = new TypedValue();
        int id = resources.getIdentifier(name, "dimen", "android");
        if (id != 0)
            resources.getValue(id, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : fallback;
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

    @RequiresApi(api = VERSION_CODES.O)
    @Override
    public void onUserLeaveHint() {
        if (!prefs.PIP.get() || !hasPipPermission(this) || !getLorieView().connected())
            return;

        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder();
        Rational aspectRatio = getLorieView().getScreenAspectRatio();
        if (aspectRatio != null) {
            float clamped = MathUtils.clamp(aspectRatio.floatValue(), MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO);
            if (clamped != aspectRatio.floatValue())
                // Truncating instead of rounding keeps the ratio from landing back outside of the range.
                aspectRatio = clamped > 1 ? new Rational((int) (clamped * 1000), 1000) : new Rational(1000, (int) (1000 / clamped));
            params.setAspectRatio(aspectRatio);
        }

        getLorieView().freezeDimensions(true);
        if (!enterPictureInPictureMode(params.build()))
            getLorieView().freezeDimensions(false);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        getLorieView().onPictureInPictureModeChanged(isInPictureInPictureMode);
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        setTerminalToolbarView();
        if (!isInPictureInPictureMode)
            applyWindowSettings();

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        Log.d("MainActivity", "Toggling keyboard visibility");
        LorieView view = getInstance().getLorieView();
        if (!externalKeyboardConnected || showIMEWhileExternalConnected)
            view.toggleKeyboardVisible();
        else
            view.setKeyboardVisible(false);
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = getLorieView().connected();

            // A picture-in-picture window has nothing to show without a client, and there is no way
            // back to the normal size from it, so the window is closed.
            if (!connected && isInPictureInPictureMode) {
                finish();
                return;
            }

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
                if (gamepadHandler != null) gamepadHandler.resyncToLorie();
                refreshLoadedPreset(false);
                isPresetLoaded = true;
            }
            onWindowFocusChanged(hasWindowFocus());
            applyWindowSettings();
        });
    }

    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return getInstance().getLorieView().connected();
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
            getLorieView().setKeyboardVisible(false);
        getLorieView().requestFocus();
    }
}
