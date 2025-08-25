package com.termux.x11.ipc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class GamepadIpc implements Runnable {

    // Protocol
    public static final int CODE_GET_GAMEPAD        = 8;
    public static final int CODE_GAMEPAD_STATE      = 9;
    public static final int CODE_RELEASE_GAMEPAD    = 10;
    public static final int CODE_SET_RUMBLE         = 11;

    public static final int FLAG_DINPUT_MAPPER_XINPUT = 0x02;
    public static final int FLAG_INPUT_TYPE_XINPUT    = 0x04;
    public static final int FLAG_INPUT_TYPE_DINPUT    = 0x08;

    public enum HandshakeFormat { NEW, LEGACY, BOTH, NONE }
    private final Object peerLock = new Object();
    private final java.util.HashSet<SocketAddress> peers       = new java.util.HashSet<SocketAddress>();
    private final java.util.HashSet<SocketAddress> xinputPeers = new java.util.HashSet<SocketAddress>(); // subset din peers


    // Config
    private final String bindHost;   // ex: "127.0.0.1"
    private final int clientPort;    // unde ascultă Android (DLL-urile trimit aici)
    private final int serverPort;    // (nefolosit direct aici, dar îl păstrăm dacă vrei să ai un fallback)
    private InetSocketAddress serverAddr;
    private final int gamepadId;     // ex: 1
    private volatile int pumpHz = 125;
    private volatile String currentName = "Termux-X12 Pad";
    private final HandshakeFormat hsFormat;

    public void setName(String n){ if (n != null && !n.isEmpty()) currentName = n; }
    public void setPumpHz(int hz){ pumpHz = Math.max(10, Math.min(500, hz)); }

    // Runtime
    private DatagramSocket rxSocket;
    private DatagramSocket txSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean statePumpRunning = new AtomicBoolean(false);
    private Thread rxThread;

    private GamepadState lastState = new GamepadState().neutral();

    // Listener
    public interface Listener {
        int  onGetGamepadRequested();
        void onRumble(int left, int right, int durationMs);
        void onRelease();
        default void onLog(String msg) {}
    }
    private final Listener listener;

    public GamepadIpc(String host, int cli, int srv, int id,
                      Listener listener, HandshakeFormat fmt) {
        this.bindHost   = host;
        this.clientPort = cli;
        this.serverPort = srv;
        this.gamepadId  = id;                       // <- corect, nu hardcodat 1
        this.listener   = listener;
        this.hsFormat   = (fmt != null ? fmt : HandshakeFormat.BOTH);
    }

    // lifecycle
    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        try {
            txSocket   = new DatagramSocket();
            serverAddr = new InetSocketAddress(bindHost, serverPort);       // dacă vrei să trimiți și “direct”
            rxSocket   = new DatagramSocket(null);
            rxSocket.setReuseAddress(true);
            rxSocket.bind(new InetSocketAddress(bindHost, clientPort));      // <- ascultăm aici
        } catch (Exception e) {
            log("socket init error: " + e);
            stop();
            return;
        }
        rxThread = new Thread(this, "GamepadIpc-UDP-Rx");
        rxThread.start();
        log("GamepadIpc started (listen " + bindHost + ":" + clientPort + ")");
    }

    public synchronized void stop() {
        running.set(false);
        connected.set(false);
        statePumpRunning.set(false);
        peers.clear();
        try { if (rxSocket != null) rxSocket.close(); } catch (Exception ignored) {}
        try { if (txSocket != null) txSocket.close(); } catch (Exception ignored) {}
        rxSocket = null;
        txSocket = null;
        synchronized (peerLock) {
            peers.clear();
            xinputPeers.clear();
        }

    }

    public void updateState(GamepadState s) { lastState = s; }

    @Override public void run() {
        final byte[] buf = new byte[64];
        final DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                rxSocket.receive(pkt);
                if (pkt.getLength() <= 0) continue;

                final int code = buf[0] & 0xFF;

                // Dacă handshake-ul e oprit, ignoră total HELLO/GET_GAMEPAD
                if (hsFormat == HandshakeFormat.NONE) {
                    if (code == 1 /*HELLO*/ || code == CODE_GET_GAMEPAD) {
                        // nu adăuga peers, nu porni pump
                        log("handshake NONE: ignoring code=" + code);
                        continue;
                    }
                    // poți lăsa RUMBLE/RELEASE să treacă sau să le ignori, la alegere
                }

                if (code == 1) { // HELLO XInput
                    final SocketAddress who = pkt.getSocketAddress();
                    synchronized (peerLock) {
                        xinputPeers.add(who);
                        peers.add(who);
                    }
                    log("HELLO from " + who + " -> mark as XINPUT");
                    continue;
                }

                if (code == CODE_GET_GAMEPAD) {
                    final SocketAddress who = pkt.getSocketAddress();
                    synchronized (peerLock) { peers.add(who); }

                    boolean reqIsXinput = (buf[1] == 1);
                    boolean sent = false;
                    try {
                        switch (hsFormat) {
                            case NEW:
                                sendXInput(FLAG_INPUT_TYPE_XINPUT, who);
                                sent = true; break;
                            case LEGACY:
                                sendDInput(FLAG_INPUT_TYPE_DINPUT | FLAG_DINPUT_MAPPER_XINPUT, who);
                                sent = true; break;
                            case BOTH:
                                if (reqIsXinput)
                                    sendXInput(FLAG_INPUT_TYPE_XINPUT, who);
                                else
                                    sendDInput(FLAG_INPUT_TYPE_DINPUT | FLAG_DINPUT_MAPPER_XINPUT, who);
                                sent = true; break;
                            case NONE:
                            default:
                                // nu răspunde
                                break;
                        }
                    } catch (IOException ex) { log("handshake send error: " + ex); }

                    if (sent) {
                        connected.set(true);
                        startStatePumpIfNeeded();
                    }
                    continue;
                }

                if (code == CODE_SET_RUMBLE) {
                    if (pkt.getLength() >= 12) {
                        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                        int left     = bb.getShort(6)  & 0xFFFF;
                        int right    = bb.getShort(8)  & 0xFFFF;
                        int duration = bb.getShort(10) & 0xFFFF;
                        if (listener != null) listener.onRumble(left, right, duration);
                    }
                    continue;
                }

                if (code == CODE_RELEASE_GAMEPAD) {
                    final SocketAddress who = pkt.getSocketAddress();
                    synchronized (peerLock) {
                        peers.remove(who);
                        xinputPeers.remove(who);
                    }
                    if (peers.isEmpty()) connected.set(false);
                    log("<- RELEASE_GAMEPAD from " + who);
                    if (listener != null) listener.onRelease();
                    continue;
                }
            } catch (Exception e) {
                if (running.get()) log("rx error: " + e);
            }
        }
    }

    // transmit
    private void sendGetGamepadReply(int ignoredFlags, HandshakeFormat fmt, SocketAddress dst) throws IOException {
        switch (fmt) {
            case NEW: {
                int flags = FLAG_INPUT_TYPE_XINPUT;
                sendXInput(flags, dst);
                break;
            }
            case LEGACY: {
                int flags = FLAG_INPUT_TYPE_DINPUT | FLAG_DINPUT_MAPPER_XINPUT;
                sendDInput(flags, dst);
                break;
            }
            case BOTH: {
                // Trimite ambele, în ordine XInput apoi DInput.
                // Fiecare cu flag-urile potrivite formatului.
                sendXInput(FLAG_INPUT_TYPE_XINPUT, dst);
                sendDInput(FLAG_INPUT_TYPE_DINPUT | FLAG_DINPUT_MAPPER_XINPUT, dst);
                break;
            }
            case NONE:
            default:
                // nu trimite nimic
                break;
        }
    }
    // XInput: [0]=8, [1]=1, *(+2)=id, [6]=flags, name NUL @ +7..
    private void sendXInput(int flags, SocketAddress dst) throws IOException {
        byte[] buf = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) CODE_GET_GAMEPAD);
        bb.put(1, (byte) 1);
        bb.putInt(2, gamepadId);
        bb.put(6, (byte) (flags & 0xFF));

        String safe = utf8SafeTruncate(
                currentName != null ? currentName : "Termux-X11 Pad",
                56 // 64 total - 7 header - 1 NUL
        );
        byte[] nb = safe.getBytes(StandardCharsets.UTF_8);
        int off = 7, len = Math.min(nb.length, buf.length - off - 1);
        System.arraycopy(nb, 0, buf, off, len);
        buf[off + len] = 0;

        txSocket.send(new DatagramPacket(buf, buf.length, dst));
        log("-> GET_GAMEPAD NEW id=" + gamepadId + " flags=0x" + Integer.toHexString(flags));
    }


    // DInput (legacy v1): [0]=8, *(+1)=id, [5]=flags, *(+6)=name_len, name @ +10..
    private void sendDInput(int flags, SocketAddress dst) throws IOException {
        byte[] buf = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) CODE_GET_GAMEPAD);
        bb.putInt(1, gamepadId);
        bb.put(5, (byte) (flags & 0xFF));

        String name = currentName != null ? currentName : "Termux-X11 Pad";
// LEGACY: max 53 bytes pt nume
        String safe = utf8SafeTruncate(name, 53);
        byte[] nb = safe.getBytes(StandardCharsets.UTF_8);
        int len = nb.length;
        bb.putInt(6, len);
        System.arraycopy(nb, 0, buf, 10, len);
        buf[10 + len] = 0;

        txSocket.send(new DatagramPacket(buf, buf.length, dst));
        log("-> GET_GAMEPAD LEGACY id=" + gamepadId + " flags=0x" + Integer.toHexString(flags));
    }

    public void sendState(GamepadState s) {
        lastState = s;

        byte[] buf = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) CODE_GAMEPAD_STATE);
        bb.put(1, (byte) 1);
        bb.putInt(2, gamepadId);
        bb.putShort(6,  (short) (s.buttons & 0xFFFF));
        bb.put(8,      (byte)  (s.dpad & 0xFF));
        bb.putShort(9,  (short) s.thumb_lx);
        bb.putShort(11, (short) s.thumb_ly);
        bb.putShort(13, (short) s.thumb_rx);
        bb.putShort(15, (short) s.thumb_ry);
        bb.put(17,     (byte)  (s.left_trigger & 0xFF));
        bb.put(18,     (byte)  (s.right_trigger & 0xFF));

        java.util.ArrayList<SocketAddress> targets;
        synchronized (peerLock) {
            targets = new java.util.ArrayList<SocketAddress>(peers);
        }
        for (SocketAddress dst : targets) {
            try { txSocket.send(new DatagramPacket(buf, buf.length, dst)); }
            catch (Exception e) { log("state send err to " + dst + ": " + e); }
        }
    }

    /** Trimite RELEASE către toți peers. */
    public void sendRelease() {
        try (DatagramSocket tx = new DatagramSocket()) {
            byte[] buf = new byte[64]; buf[0] = (byte) CODE_RELEASE_GAMEPAD;
            for (SocketAddress dst : peers) {
                tx.send(new DatagramPacket(buf, buf.length, dst));
            }
        } catch (Exception ignored) {}
    }

    private void startStatePumpIfNeeded() {
        if (statePumpRunning.getAndSet(true)) return;
        new Thread(() -> {
            long next = System.currentTimeMillis();
            while (running.get() && connected.get()) {
                try { sendState(lastState); } catch (Exception e) { log("statePump send err: " + e); }
                long period = 1000L / Math.max(1, pumpHz);
                next += period;
                long sleep = next - System.currentTimeMillis();
                if (sleep < 1) sleep = 1;
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
            statePumpRunning.set(false);
        }, "GamepadIpc-StatePump").start();
    }

    private void log(String msg) { if (listener != null) listener.onLog("[GamepadIpc] " + msg); }

    private static String utf8SafeTruncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        int end = maxBytes;
        // verifică ultimul BYTE inclus (end-1), nu end
        while (end > 0 && (b[end - 1] & 0b1100_0000) == 0b1000_0000) end--;
        if (end <= 0) end = maxBytes; // fallback
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    // Stare
    public static class GamepadState {
        public int buttons;
        public int dpad = 255;
        public int thumb_lx, thumb_ly;
        public int thumb_rx, thumb_ry;
        public int left_trigger, right_trigger;
        public GamepadState neutral() {
            buttons = 0; dpad = 255;
            thumb_lx = thumb_ly = thumb_rx = thumb_ry = 0;
            left_trigger = right_trigger = 0;
            return this;
        }
    }
}