/*
 * Copyright © 2026 smart-car-software contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.smartcar.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place cards are shown: takes requests from {@link RequestReceiver}, lines them up in a
 * {@link CardQueue}, puts the current one on the cluster (and on the head unit when the wheel
 * cannot answer it there), reads the wheel while it is up and sends the answer back to the app
 * that asked.
 *
 * <p>Owns everything a card needs for as long as one is up — the key log, the held media keys,
 * the volume guard, the chime — so two clients never compete for any of it. Runs in the
 * foreground only while there is something to show, and stops itself a minute after the line runs
 * dry.
 */
public class NotificationService extends Service {
    private static final String TAG = "NotificationService";
    static final String ACTION_START = "com.smartcar.notify.action.START";
    private static final String CHANNEL_ID = "queue";
    private static final int NOTIFICATION_ID = 1;
    private static final int PERMISSION_NOTIFICATION_ID = 2;

    /** Where the card goes on a Monjaro's cluster, measured off photos of the real panel. */
    static final int CARD_WIDTH_PX = 460;
    static final int CARD_TOP_PX = 288;
    /** The head unit's copy: the same card, larger, to be read and touched at arm's length. */
    static final float HEAD_UNIT_SCALE = 1.6f;

    private static final long IDLE_STOP_MS = 60_000L;
    private static final long EXPIRY_TICK_MS = 5_000L;
    /** Between two cards the key log stays connected: reconnecting takes a second or more. */
    private static final long WHEEL_LINGER_MS = 15_000L;
    /** After the last card the media keys stay held this long, for the answering press's echo. */
    private static final long MEDIA_LINGER_MS = 1_500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CardQueue queue = new CardQueue();

    private WheelKeyLog keyLog;
    private MediaKeyCatcher mediaKeys;
    private VolumeGuard volume;
    private SoundPool sounds;
    private int chimeId;
    private boolean chimeLoaded;

    private boolean wheelConnected;
    private boolean mediaHeld;

    // The card on screen.
    private Card shown;
    private int selected;
    private int firstVisible;
    private CardView clusterView;
    private WindowManager clusterWm;
    private CardView headView;
    private WindowManager headWm;
    private WheelNavigator.Action lastAction = WheelNavigator.Action.NONE;
    private long lastActionAt;

    private final Runnable timeout = this::onTimeout;
    private final Runnable idleStop = () -> {
        if (queue.isEmpty()) {
            DebugLog.i(TAG, "Idle, stopping");
            stopSelf();
        }
    };
    private final Runnable stopWheel = () -> {
        if (shown == null && keyLog != null) {
            keyLog.stop();
        }
    };
    private final Runnable releaseMedia = () -> {
        if (shown == null && mediaHeld) {
            mediaKeys.stop();
            mediaHeld = false;
            DebugLog.i(TAG, "Media keys released");
        }
    };
    private final Runnable expiryTick = new Runnable() {
        @Override
        public void run() {
            for (Card c : queue.dropExpired(System.currentTimeMillis())) {
                reply(c, NotifyContract.RESULT_EXPIRED, -1, null);
            }
            if (queue.waitingCount() > 0) {
                main.postDelayed(this, EXPIRY_TICK_MS);
            }
            updateForegroundNotification();
        }
    };

    /** How client requests and the settings screen's test buttons reach the service. */
    static void deliver(Context context, Intent request) {
        Intent i = new Intent(request);
        i.setClass(context, NotificationService.class);
        context.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        keyLog = new WheelKeyLog(this);
        mediaKeys = new MediaKeyCatcher(this);
        volume = new VolumeGuard(this);
        createChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        initSound();
        DebugLog.i(TAG, "Started (" + AppSettings.describe(this) + ")");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start must reach startForeground, or the system kills the app five seconds on.
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        if (intent != null) {
            String action = intent.getAction();
            if (NotifyContract.ACTION_SHOW.equals(action)) {
                onShow(intent);
            } else if (NotifyContract.ACTION_CANCEL.equals(action)) {
                onCancel(intent.getStringExtra(NotifyContract.EXTRA_ID));
            }
        }
        if (queue.isEmpty()) {
            scheduleIdleStop();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- requests ----

    private void onShow(Intent intent) {
        String id = intent.getStringExtra(NotifyContract.EXTRA_ID);
        String typeName = intent.getStringExtra(NotifyContract.EXTRA_TYPE);
        Card.Type type = Card.Type.parse(typeName);
        String title = intent.getStringExtra(NotifyContract.EXTRA_TITLE);
        String[] options = intent.getStringArrayExtra(NotifyContract.EXTRA_OPTIONS);
        PendingIntent replyTo = intent.getParcelableExtra(NotifyContract.EXTRA_REPLY);
        String error = Card.invalid(id, type, title, options);
        if (error != null) {
            DebugLog.i(TAG, "Refused " + id + " from " + creator(replyTo) + ": " + error);
            sendReply(replyTo, id, NotifyContract.RESULT_INVALID, -1, null, error);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.i(TAG, "No overlay permission: " + id + " answered unavailable");
            sendReply(replyTo, id, NotifyContract.RESULT_UNAVAILABLE, -1, null, "no overlay permission");
            askForOverlayPermission();
            return;
        }
        long expire = intent.getLongExtra(NotifyContract.EXTRA_EXPIRE_MS, 0);
        Card card = new Card(id, type, title, intent.getStringExtra(NotifyContract.EXTRA_TEXT),
                options, intent.getIntExtra(NotifyContract.EXTRA_SELECTED, 0),
                intent.getLongExtra(NotifyContract.EXTRA_TIMEOUT_MS, 0),
                expire > 0 ? System.currentTimeMillis() + expire : 0,
                intent.getBooleanExtra(NotifyContract.EXTRA_SOUND, false), replyTo);
        main.removeCallbacks(idleStop);
        CardQueue.Offer offer = queue.offer(card);
        DebugLog.i(TAG, offer + ": " + type + " " + id + " from " + creator(replyTo)
                + " (" + queue.waitingCount() + " waiting)");
        if (offer == CardQueue.Offer.UPDATED_CURRENT) {
            // Same id, new content: redrawn in place, the chime and the timer not started over —
            // a client re-sending the same question must not restart it under the driver.
            redrawShown(card);
        } else if (offer == CardQueue.Offer.QUEUED && queue.waitingCount() == 1) {
            main.removeCallbacks(expiryTick);
            main.postDelayed(expiryTick, EXPIRY_TICK_MS);
        }
        pump();
    }

    private void onCancel(String id) {
        if (id == null) {
            return;
        }
        boolean wasShown = shown != null && shown.id.equals(id);
        Card c = queue.cancel(id);
        if (c == null) {
            return;
        }
        DebugLog.i(TAG, "Cancelled " + id + (wasShown ? " (on screen)" : ""));
        if (wasShown) {
            teardownShown();
        }
        reply(c, NotifyContract.RESULT_CANCELLED, -1, null);
        pump();
    }

    /** Puts the next card on screen if none is up. */
    private void pump() {
        List<Card> expired = new ArrayList<>();
        Card next = queue.advance(System.currentTimeMillis(), expired);
        for (Card c : expired) {
            reply(c, NotifyContract.RESULT_EXPIRED, -1, null);
        }
        if (next != null) {
            present(next);
        }
        if (queue.isEmpty()) {
            afterLastCard();
        }
        updateForegroundNotification();
    }

    // ---- the card on screen ----

    private void present(Card card) {
        shown = card;
        selected = card.initial;
        firstVisible = CardLayout.firstVisible(selected, card.options.length,
                CardLayout.VISIBLE_ROWS, 0);
        // lastAction is kept: the media-session echo of the press that closed the previous card
        // must not answer this one.
        main.removeCallbacks(stopWheel);
        main.removeCallbacks(releaseMedia);
        startWheel();
        if (card.type == Card.Type.LIST && AppSettings.holdVolume(this) && wheelConnected) {
            volume.start();
        }
        captureRoller(card);
        showOnCluster(card);
        updateHeadUnit();
        render();
        if (card.sound) {
            chime();
        }
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, card.showForMs);
        DebugLog.i(TAG, "Showing " + card.type + " " + card.id + " (cluster " + (clusterView != null)
                + ", head unit " + (headView != null) + ")");
    }

    /** Same id re-sent while on screen: new text and options, the selection kept where it can be. */
    private void redrawShown(Card card) {
        shown = card;
        selected = Math.min(selected, Math.max(0, card.options.length - 1));
        firstVisible = CardLayout.firstVisible(selected, card.options.length,
                CardLayout.VISIBLE_ROWS, firstVisible);
        removeWindows();
        captureRoller(card);
        showOnCluster(card);
        updateHeadUnit();
        render();
    }

    /**
     * A list takes the volume roller from the volume while the media keys are held: the click
     * still reaches the key log (that is what scrolls), the volume no longer moves.
     * {@link VolumeGuard} stays as the fallback for when the session is not the one picked.
     */
    private void captureRoller(Card card) {
        boolean on = mediaHeld && card.type == Card.Type.LIST && AppSettings.holdVolume(this);
        mediaKeys.captureVolume(on,
                step -> DebugLog.each(TAG, "Volume step " + step + " taken by the session"));
    }

    private void showOnCluster(Card card) {
        if (!AppSettings.cluster(this)) {
            return;
        }
        Display display = ClusterDisplay.find(this, AppSettings.CLUSTER_DISPLAY);
        if (display == null) {
            DebugLog.i(TAG, "Cluster display #" + AppSettings.CLUSTER_DISPLAY + " not found");
            return; // The cluster is not up yet, or this is not a Monjaro.
        }
        try {
            Context c = new ContextThemeWrapper(createDisplayContext(display), R.style.Theme_Notify);
            CardView v = new CardView(c, card, 1f, null);
            clusterWm = ClusterDisplay.showOverlay(this, display, v.root,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, CARD_WIDTH_PX, CARD_TOP_PX);
            clusterView = v;
        } catch (RuntimeException e) {
            // A firmware that refuses windows there: the head unit shows the card instead.
            DebugLog.w(TAG, "Cluster card not shown", e);
        }
    }

    /** Shows or hides the head unit's touchable copy, as {@link CardLayout#needsHeadUnit} says. */
    private void updateHeadUnit() {
        if (shown == null) {
            return;
        }
        boolean need = CardLayout.needsHeadUnit(shown.type, clusterView != null, wheelConnected, mediaHeld);
        if (need == (headView != null)) {
            return;
        }
        if (!need) {
            DebugLog.i(TAG, "Head unit copy hidden");
            ClusterDisplay.removeOverlay(headWm, headView.root);
            headView = null;
            headWm = null;
            return;
        }
        Display display = ClusterDisplay.headUnit(this);
        if (display == null) {
            return;
        }
        try {
            Context c = new ContextThemeWrapper(createDisplayContext(display), R.style.Theme_Notify);
            CardView v = new CardView(c, shown, HEAD_UNIT_SCALE, this::onTap);
            headWm = ClusterDisplay.showOverlay(this, display, v.root, Gravity.CENTER,
                    Math.round(CARD_WIDTH_PX * HEAD_UNIT_SCALE), 0);
            headView = v;
            DebugLog.i(TAG, "Head unit copy shown (cluster " + (clusterView != null) + ", wheel "
                    + wheelConnected + ", media " + mediaHeld + ")");
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "Head unit card not shown", e);
        }
        render();
    }

    private void render() {
        if (clusterView != null) {
            clusterView.render(selected, firstVisible);
        }
        if (headView != null) {
            headView.render(selected, firstVisible);
        }
    }

    private void onTap(int index) {
        if (shown == null) {
            return;
        }
        DebugLog.i(TAG, "Tapped " + index + " on " + shown.id);
        if (shown.type == Card.Type.INFO) {
            finishShown(NotifyContract.RESULT_DISMISSED, -1);
        } else if (index >= 0 && index < shown.options.length) {
            finishShown(NotifyContract.RESULT_ANSWERED, index);
        }
    }

    private void onTimeout() {
        if (shown == null) {
            return;
        }
        DebugLog.i(TAG, "Timed out: " + shown.id);
        finishShown(shown.type == Card.Type.INFO
                ? NotifyContract.RESULT_DISMISSED : NotifyContract.RESULT_TIMEOUT, -1);
    }

    private void finishShown(String result, int index) {
        Card c = queue.finishCurrent();
        teardownShown();
        if (c != null) {
            reply(c, result, index, index >= 0 && index < c.options.length ? c.options[index] : null);
        }
        pump();
    }

    private void teardownShown() {
        main.removeCallbacks(timeout);
        removeWindows();
        volume.stop();
        mediaKeys.captureVolume(false, null); // The roller is the volume again at once.
        shown = null;
    }

    private void removeWindows() {
        if (clusterView != null) {
            ClusterDisplay.removeOverlay(clusterWm, clusterView.root);
        }
        if (headView != null) {
            ClusterDisplay.removeOverlay(headWm, headView.root);
        }
        clusterView = null;
        clusterWm = null;
        headView = null;
        headWm = null;
    }

    private void afterLastCard() {
        // The key log reports a press before the media session does (by 100–250 ms in the car), so
        // the press that answered is still on its way there: the music gets its keys back only once
        // it has arrived and been swallowed. The key log lingers longer, for a card that follows.
        main.removeCallbacks(releaseMedia);
        main.postDelayed(releaseMedia, MEDIA_LINGER_MS);
        main.removeCallbacks(stopWheel);
        main.postDelayed(stopWheel, WHEEL_LINGER_MS);
        main.removeCallbacks(expiryTick);
        scheduleIdleStop();
    }

    private void scheduleIdleStop() {
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_STOP_MS);
    }

    // ---- the wheel ----

    private void startWheel() {
        if (!AppSettings.wheelKeys(this)) {
            return;
        }
        if (!keyLog.isRunning()) {
            keyLog.start((code, pressed) -> {
                if (pressed) {
                    onKey(code, false, "key log " + code);
                }
            }, connected -> {
                DebugLog.i(TAG, connected ? "Wheel key log connected" : "Wheel key log ended");
                wheelConnected = connected;
                if (connected && shown != null && shown.type == Card.Type.LIST
                        && AppSettings.holdVolume(this)) {
                    volume.start();
                }
                updateHeadUnit();
            });
        }
        if (AppSettings.muteMedia(this) && !mediaHeld) {
            // Keeps the player from acting on ⏮ ⏭ ⏯ — and is a second route for those presses
            // (after the key log's, or instead of it if the log goes quiet for them).
            mediaHeld = mediaKeys.start((code, pressed) -> {
                if (pressed) {
                    onKey(code, true, "media " + code);
                }
            });
            DebugLog.i(TAG, "Media keys " + (mediaHeld ? "held" : "NOT held (silent track failed)"));
        }
    }

    /** @param route where the press came from, for the journal */
    private void onKey(int code, boolean mediaSession, String route) {
        if (shown == null) {
            return;
        }
        WheelNavigator.Action action = WheelNavigator.forCard(shown.type, code, mediaSession);
        if (action == WheelNavigator.Action.NONE) {
            DebugLog.each(TAG, "Key ignored: " + route + " on " + shown.type);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // ⏮ ⏭ ⏯ can arrive by both routes at once; the roller only ever by the log, and quick
        // clicks of it are real, so a list's scroll is never folded as an echo.
        boolean roll = shown.type == Card.Type.LIST && action != WheelNavigator.Action.CONFIRM;
        if (!roll && WheelNavigator.isEcho(action, now, lastAction, lastActionAt)) {
            DebugLog.each(TAG, action + " via " + route + ": echo, dropped");
            return;
        }
        DebugLog.each(TAG, action + " via " + route + " on " + selected);
        lastAction = action;
        lastActionAt = now;
        if (action == WheelNavigator.Action.CONFIRM) {
            if (shown.type == Card.Type.INFO) {
                finishShown(NotifyContract.RESULT_DISMISSED, -1);
            } else {
                finishShown(NotifyContract.RESULT_ANSWERED, selected);
            }
            return;
        }
        if (roll) {
            volume.onRoll();
        }
        boolean[] all = new boolean[shown.options.length];
        java.util.Arrays.fill(all, true);
        selected = WheelNavigator.move(selected, action, all);
        firstVisible = CardLayout.firstVisible(selected, shown.options.length,
                CardLayout.VISIBLE_ROWS, firstVisible);
        render();
    }

    // ---- replies ----

    private void reply(Card c, String result, int index, String option) {
        DebugLog.i(TAG, "Reply " + c.id + ": " + result + (index >= 0 ? " " + index : ""));
        sendReply((PendingIntent) c.reply, c.id, result, index, option, null);
    }

    private void sendReply(PendingIntent to, String id, String result, int index, String option,
                           String error) {
        if (to == null) {
            return; // An information card nobody needs to hear back about.
        }
        Intent fill = new Intent();
        fill.putExtra(NotifyContract.EXTRA_ID, id);
        fill.putExtra(NotifyContract.EXTRA_RESULT, result);
        fill.putExtra(NotifyContract.EXTRA_INDEX, index);
        if (option != null) {
            fill.putExtra(NotifyContract.EXTRA_OPTION, option);
        }
        if (error != null) {
            fill.putExtra(NotifyContract.EXTRA_ERROR, error);
        }
        try {
            to.send(this, 0, fill);
        } catch (PendingIntent.CanceledException e) {
            DebugLog.w(TAG, "Reply to " + id + " not delivered", e);
        }
    }

    private static String creator(PendingIntent p) {
        return p == null ? "?" : p.getCreatorPackage();
    }

    // ---- sound and notifications ----

    private void initSound() {
        try {
            sounds = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            sounds.setOnLoadCompleteListener((pool, id, status) -> chimeLoaded = status == 0);
            chimeId = sounds.load(this, R.raw.popup_sound, 1);
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "Sound not set up", e);
        }
    }

    private void chime() {
        if (sounds != null && chimeLoaded) {
            sounds.play(chimeId, 1f, 1f, 1, 0, 1f);
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_queue), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildForegroundNotification() {
        int waiting = queue.waitingCount() + (shown != null ? 1 : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_queue, waiting))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildForegroundNotification());
        }
    }

    private void askForOverlayPermission() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(PERMISSION_NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.overlay_needed))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        // Cards still up or waiting are never shown now: their clients hear so rather than wait.
        if (shown != null) {
            Card c = queue.finishCurrent();
            teardownShown();
            if (c != null) {
                reply(c, NotifyContract.RESULT_CANCELLED, -1, null);
            }
        }
        Card c;
        while ((c = queue.advance(0, new ArrayList<>())) != null) {
            queue.finishCurrent();
            reply(c, NotifyContract.RESULT_CANCELLED, -1, null);
        }
        keyLog.stop();
        mediaKeys.stop();
        volume.stop();
        if (sounds != null) {
            sounds.release();
        }
        DebugLog.i(TAG, "Stopped");
        super.onDestroy();
    }
}
