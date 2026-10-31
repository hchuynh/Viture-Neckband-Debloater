package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/**
 * The two automatic head-gaze rules of the profiles.
 *
 * Watch mode: while a video plays full screen, head gaze is switched off so the pointer stops
 * drifting. A neckband volume button brings it back (Viture leaves those buttons to apps while head
 * gaze is off). When the video leaves full screen or stops, head gaze returns.
 * It costs nothing while nothing plays: Android reports playback changes, and only during playback
 * the screen is checked every two seconds.
 *
 * Keyboard or mouse: when an external keyboard, mouse or trackpad connects, head gaze is switched off,
 * and back on when the last one disconnects. Android reports device changes; nothing is polled.
 */
final class AutoGaze implements InputManager.InputDeviceListener {
    private static final long POLL_MS = 2000, REARM_MS = 3000;

    private final AccessibilityService s;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final InputManager im;
    private final Playback playback = new Playback(this);
    private boolean watch, peripheral;
    private boolean watchHolds, watchDismissed, peripheralHolds;
    private final Runnable poll = this::check;
    private long lastActivity = 0;   // last time the pointer moved over something or a button was used

    /** The pointer moved over something: the person is using head gaze right now. */
    void activity() { lastActivity = android.os.SystemClock.uptimeMillis(); }

    boolean wantsHover() { return watch; }

    AutoGaze(AccessibilityService s) {
        this.s = s;
        am = s.getSystemService(AudioManager.class);
        im = s.getSystemService(InputManager.class);
    }

    void configure(boolean watch, boolean peripheral) {
        if (watch != this.watch) {
            this.watch = watch;
            if (watch) {
                am.registerAudioPlaybackCallback(playback, h);
                s.getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor(HeadGazeTile.KEY), false, gazeObserver);
                check();
            } else {
                am.unregisterAudioPlaybackCallback(playback);
                s.getContentResolver().unregisterContentObserver(gazeObserver);
                h.removeCallbacks(poll);
                release(true);
            }
        }
        if (peripheral != this.peripheral) {
            this.peripheral = peripheral;
            if (peripheral) { im.registerInputDeviceListener(this, h); devicesChanged(); }
            else { im.unregisterInputDeviceListener(this); if (peripheralHolds) { peripheralHolds = false; setGaze(true); } }
        }
    }

    void stop() { configure(false, false); }

    private boolean gazeOn() { return Prefs.getInt(s, HeadGazeTile.KEY, 0) == 1; }
    private long ownWriteUntil = 0;
    private void setGaze(boolean on) {
        ownWriteUntil = android.os.SystemClock.uptimeMillis() + 800;
        Prefs.putInt(s, HeadGazeTile.KEY, on ? 1 : 0);
    }

    /**
     * Head gaze was switched on by the person (panel, tile, mode button): give them the same grace as
     * a volume button, so watch mode waits until the pointer rests again.
     */
    void gazeChanged() {
        if (!watch || android.os.SystemClock.uptimeMillis() < ownWriteUntil || !gazeOn()) return;
        watchHolds = false;
        watchDismissed = true;
        activity();
        h.removeCallbacks(poll);
        h.postDelayed(poll, 1000);
    }

    private final GazeObserver gazeObserver = new GazeObserver(this);

    static final class GazeObserver extends android.database.ContentObserver {
        private final AutoGaze g;
        GazeObserver(AutoGaze g) { super(new Handler(Looper.getMainLooper())); this.g = g; }
        @Override public void onChange(boolean self) { g.gazeChanged(); }
    }

    // ---------------------------------------------------------------- watch mode
    void playbackChanged() { h.removeCallbacks(poll); check(); }

    private void check() {
        boolean playing = am.isMusicActive();
        boolean full = playing && fullScreen();
        if (full) {
            // brought back with a volume button: step aside again once the pointer rests for a few seconds
            if (watchDismissed && android.os.SystemClock.uptimeMillis() - lastActivity >= REARM_MS) watchDismissed = false;
            if (!watchHolds && !watchDismissed && !peripheralHolds && gazeOn()) { setGaze(false); watchHolds = true; }
        } else release(false);
        if (playing) { h.removeCallbacks(poll); h.postDelayed(poll, watchDismissed ? 1000 : POLL_MS); }
    }

    /** Video left full screen or stopped: give head gaze back if watch mode took it. */
    private void release(boolean always) {
        if (watchHolds) setGaze(true);
        watchHolds = false;
        watchDismissed = false;
    }

    /** True while watch mode keeps head gaze off: the volume buttons then bring it back. */
    boolean holdsGaze() { return watchHolds; }

    /** A neckband volume button while watch mode holds head gaze off: give it back for this video. */
    void volumePressed() {
        if (!watchHolds) return;
        watchHolds = false;
        watchDismissed = true;   // until the pointer rests again for REARM_MS
        activity();
        setGaze(true);
    }

    /** Full screen: no status bar shown and the app in front covers the whole display. */
    private boolean fullScreen() {
        Rect screen = s.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
        List<AccessibilityWindowInfo> ws = s.getWindows();
        Rect r = new Rect();
        boolean app = false;
        for (AccessibilityWindowInfo w : ws) {
            w.getBoundsInScreen(r);
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && r.top <= 0 && r.height() < screen.height() / 6
                    && r.width() > screen.width() / 2) return false;   // the status bar is visible
            if (!app && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (r.width() < screen.width() - 2 || r.height() < screen.height() - 2) return false;
                app = true;
            }
        }
        return app;
    }

    static final class Playback extends AudioManager.AudioPlaybackCallback {
        private final AutoGaze g;
        Playback(AutoGaze g) { this.g = g; }
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) { g.playbackChanged(); }
    }

    // ---------------------------------------------------------------- keyboard or mouse
    @Override public void onInputDeviceAdded(int id) { devicesChanged(); }
    @Override public void onInputDeviceRemoved(int id) { devicesChanged(); }
    @Override public void onInputDeviceChanged(int id) { devicesChanged(); }

    private void devicesChanged() {
        boolean present = peripheralPresent();
        if (present && !peripheralHolds) {
            if (watchHolds) { watchHolds = false; peripheralHolds = true; }   // already off: keep it off for the mouse
            else if (gazeOn()) { setGaze(false); peripheralHolds = true; }
        } else if (!present && peripheralHolds) {
            peripheralHolds = false;
            setGaze(true);
        }
    }

    /** An external keyboard, mouse or trackpad. Built-in and virtual devices (the neckband, the glasses) do not count. */
    static boolean peripheralPresent() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || !d.isExternal()) continue;
            String n = d.getName() == null ? "" : d.getName().toLowerCase();
            if (n.contains("viture")) continue;
            boolean pointer = d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_TOUCHPAD);
            boolean keyboard = d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            if (pointer || keyboard) return true;
        }
        return false;
    }
}
