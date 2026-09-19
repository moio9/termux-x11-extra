package com.termux.x11.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Plays the helper's 48 kHz stereo PCM16 stream under the Android app UID. */
public final class PulseAudioPlayback {
    private static final String TAG = "X11Audio";
    private Session session;

    public synchronized void setEnabled(boolean enabled) {
        if (enabled && session == null) {
            session = new Session();
            session.start();
        } else if (!enabled && session != null) {
            session.cancel();
            session = null;
        }
    }

    // Every start has its own cancellation state: a retiring thread cannot
    // resume when the preference is quickly switched off and back on.
    private static final class Session extends Thread {
        private volatile boolean cancelled;
        private Socket socket;

        Session() { super("TermuxX11Audio"); }

        synchronized void cancel() {
            cancelled = true;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            interrupt();
        }

        private synchronized boolean attach(Socket connection) {
            if (cancelled) return false;
            socket = connection;
            return true;
        }

        @Override public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            boolean reportedFailure = false;
            while (!cancelled) {
                AudioTrack track = null;
                try (Socket connection = new Socket()) {
                    if (!attach(connection)) break;
                    connection.connect(new InetSocketAddress("127.0.0.1", 4714), 1500);
                    connection.setTcpNoDelay(true);
                    connection.setSoTimeout(3000);
                    if (cancelled) break;

                    AudioAttributes.Builder attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
                    if (Build.VERSION.SDK_INT >= 29)
                        attributes.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
                    int minimum = AudioTrack.getMinBufferSize(48000,
                            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minimum <= 0) throw new IOException("Unsupported audio format");
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(attributes.build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(minimum, 9600))
                            .setTransferMode(AudioTrack.MODE_STREAM).build();
                    track.play();
                    Log.i(TAG, "Playing PulseAudio stream; playback capture allowed");
                    reportedFailure = false;
                    InputStream input = connection.getInputStream();
                    byte[] buffer = new byte[4096];
                    int pending = 0;
                    while (!cancelled) {
                        int count = input.read(buffer, pending, buffer.length - pending);
                        if (count < 0) throw new IOException("Audio stream closed");
                        int available = pending + count;
                        int frames = available - available % 4;
                        int offset = 0;
                        while (offset < frames && !cancelled) {
                            int written = track.write(buffer, offset, frames - offset,
                                    AudioTrack.WRITE_NON_BLOCKING);
                            if (written < 0) throw new IOException("AudioTrack write: " + written);
                            if (written == 0) Thread.sleep(5);
                            offset += written;
                        }
                        pending = available - frames;
                        System.arraycopy(buffer, frames, buffer, 0, pending);
                    }
                } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                    if (!cancelled && !reportedFailure) {
                        Log.w(TAG, "Audio unavailable; waiting for the Termux audio helper", e);
                        reportedFailure = true;
                    }
                } catch (InterruptedException e) {
                    break;
                } finally {
                    if (track != null) {
                        try { track.pause(); track.flush(); }
                        catch (IllegalStateException ignored) { }
                        track.release();
                    }
                    synchronized (this) { socket = null; }
                }
                if (!cancelled) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException e) { break; }
                }
            }
        }
    }
}
