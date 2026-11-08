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

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Settings, the three test cards, and the diagnostics the wheel still needs: the key log as it
 * comes and the volume-roller trace. Everything a client app used to carry for the cluster lives
 * here now, once.
 */
public class MainActivity extends AppCompatActivity {
    private static final int MAX_DIAG_CHARS = 6_000;

    private RootStream rootCheck;
    private RootStream keyLog;
    private VolumeTrace trace;
    private TextView wheelStatus;
    private TextView overlay;
    private TextView diag;
    private Button keyLogButton;
    private Button traceButton;
    private EditText logUrl;
    private EditText logUser;
    private EditText logPassword;
    private int testCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        rootCheck = new RootStream(this);
        keyLog = new RootStream(this);
        trace = new VolumeTrace(this);

        overlay = findViewById(R.id.textOverlay);
        findViewById(R.id.btnOverlay).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));

        MaterialSwitch cluster = findViewById(R.id.switchCluster);
        cluster.setChecked(AppSettings.cluster(this));
        cluster.setOnCheckedChangeListener((b, on) -> AppSettings.setCluster(this, on));

        MaterialSwitch wheel = findViewById(R.id.switchWheelKeys);
        MaterialSwitch mute = findViewById(R.id.switchMuteMedia);
        MaterialSwitch hold = findViewById(R.id.switchHoldVolume);
        wheelStatus = findViewById(R.id.textWheelStatus);
        wheel.setChecked(AppSettings.wheelKeys(this));
        mute.setChecked(AppSettings.muteMedia(this));
        hold.setChecked(AppSettings.holdVolume(this));
        mute.setEnabled(wheel.isChecked());
        hold.setEnabled(wheel.isChecked());
        wheel.setOnCheckedChangeListener((b, on) -> {
            AppSettings.setWheelKeys(this, on);
            mute.setEnabled(on);
            hold.setEnabled(on);
            if (on) {
                checkRoot();
            } else {
                rootCheck.stop();
                wheelStatus.setText("");
            }
        });
        mute.setOnCheckedChangeListener((b, on) -> AppSettings.setMuteMedia(this, on));
        hold.setOnCheckedChangeListener((b, on) -> AppSettings.setHoldVolume(this, on));
        if (wheel.isChecked()) {
            checkRoot();
        }

        findViewById(R.id.btnTestInfo).setOnClickListener(v -> test(NotifyContract.TYPE_INFO,
                getString(R.string.test_info_title), getString(R.string.test_info_text), null));
        findViewById(R.id.btnTestQuestion).setOnClickListener(v -> test(NotifyContract.TYPE_QUESTION,
                getString(R.string.test_question_title), null,
                new String[]{getString(R.string.answer_yes), getString(R.string.answer_no),
                        getString(R.string.answer_later)}));
        findViewById(R.id.btnTestList).setOnClickListener(v -> test(NotifyContract.TYPE_LIST,
                getString(R.string.test_list_title), null,
                getResources().getStringArray(R.array.test_list_rows)));

        diag = findViewById(R.id.textDiag);
        keyLogButton = findViewById(R.id.btnKeyLog);
        keyLogButton.setOnClickListener(v -> toggleKeyLog());
        traceButton = findViewById(R.id.btnVolumeTrace);
        traceButton.setOnClickListener(v -> startTrace());
        findViewById(R.id.btnSendLogs).setOnClickListener(v -> {
            saveLogServer();
            Toast.makeText(this, R.string.logs_sending, Toast.LENGTH_SHORT).show();
            LogSender.send(this, diag.getText().toString(),
                    msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        });

        logUrl = findViewById(R.id.editLogUrl);
        logUser = findViewById(R.id.editLogUser);
        logPassword = findViewById(R.id.editLogPassword);
        logUrl.setText(AppSettings.logUrl(this));
        logUser.setText(AppSettings.logUser(this));
        logPassword.setText(AppSettings.logPassword(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        overlay.setText(Settings.canDrawOverlays(this)
                ? R.string.overlay_granted : R.string.overlay_needed);
        findViewById(R.id.btnOverlay).setEnabled(!Settings.canDrawOverlays(this));
    }

    @Override
    protected void onPause() {
        saveLogServer();
        super.onPause();
    }

    private void saveLogServer() {
        AppSettings.setLogServer(this, text(logUrl), text(logUser), text(logPassword));
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    /** Through the same door a client uses, so the test proves the whole path. */
    private void test(String type, String title, String text, String[] options) {
        Intent i = new Intent(NotifyContract.ACTION_SHOW);
        i.setClassName(getPackageName(), NotifyContract.RECEIVER);
        i.putExtra(NotifyContract.EXTRA_ID, getPackageName() + ":test:" + (++testCount));
        i.putExtra(NotifyContract.EXTRA_TYPE, type);
        i.putExtra(NotifyContract.EXTRA_TITLE, title);
        if (text != null) {
            i.putExtra(NotifyContract.EXTRA_TEXT, text);
        }
        if (options != null) {
            i.putExtra(NotifyContract.EXTRA_OPTIONS, options);
        }
        i.putExtra(NotifyContract.EXTRA_SOUND, true);
        i.putExtra(NotifyContract.EXTRA_REPLY, PendingIntent.getBroadcast(this, testCount,
                new Intent(this, TestReplyReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        sendBroadcast(i);
    }

    private void checkRoot() {
        wheelStatus.setText(R.string.settings_check_root_running);
        StringBuilder out = new StringBuilder();
        rootCheck.start("id -un", null, line -> {
            if (!AppSettings.wheelKeys(this)) {
                return; // Switched off meanwhile.
            }
            if (line == null) {
                String s = out.toString();
                boolean root = s.contains("\nroot") || s.startsWith("root");
                wheelStatus.setText(root ? getString(R.string.settings_check_root_ok)
                        : getString(R.string.settings_check_root_failed, s.trim()));
            } else {
                out.append(line).append('\n');
            }
        });
    }

    private boolean keyLogOn;
    private static final long HOLD_AT_MS = 2_000L;

    /** Every line InputImpl writes — presses, releases, the roller — as it comes. */
    private void toggleKeyLog() {
        if (keyLogOn) {
            keyLog.stop();
            return;
        }
        keyLogOn = true;
        keyLogButton.setText(R.string.diag_key_log_stop);
        diag.setText("");
        keyLog.start("logcat -b main -v time -T 1 InputImpl:V *:S", null, line -> {
            if (line == null) {
                keyLogOn = false;
                keyLogButton.setText(R.string.diag_key_log_start);
                return;
            }
            appendDiag(line);
        });
    }

    private void startTrace() {
        if (trace.isRunning()) {
            return;
        }
        if (keyLogOn) {
            keyLog.stop();
        }
        traceButton.setEnabled(false);
        diag.setText(getString(R.string.diag_volume_trace_running, VolumeTrace.SECONDS));
        // Held the way a list card holds them, so the trace shows what still gets past.
        // Taken a moment in, so the "before" dumps and the start of the hold are both in the trace.
        MediaKeyCatcher held = new MediaKeyCatcher(this);
        Runnable hold = () -> {
            boolean holding = held.start((code, pressed) -> {
                if (pressed) {
                    runOnUiThread(() -> appendDiag("media key " + code + " taken by the session"));
                }
            });
            held.captureVolume(holding, step -> runOnUiThread(
                    () -> appendDiag("volume step " + step + " taken by the session")));
            appendDiag(holding ? "media keys and roller held" : "media keys NOT held");
        };
        Runnable release = () -> {
            held.stop();
            appendDiag("media keys released");
        };
        diag.postDelayed(hold, HOLD_AT_MS);
        diag.postDelayed(release, VolumeTrace.RELEASE_AT_SECONDS * 1000L);
        trace.start(capture -> {
            diag.removeCallbacks(hold);
            diag.removeCallbacks(release);
            held.stop();
            traceButton.setEnabled(true);
            int lines = capture.isEmpty() ? 0 : capture.split("\n").length;
            appendDiag(getString(R.string.diag_volume_trace_done, lines));
            saveLogServer();
            LogSender.send(this, null, capture,
                    msg -> {
                        appendDiag(msg);
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    });
        });
    }

    private void appendDiag(String line) {
        CharSequence old = diag.getText();
        String s = old + (old.length() > 0 ? "\n" : "") + line;
        if (s.length() > MAX_DIAG_CHARS) {
            s = s.substring(s.length() - MAX_DIAG_CHARS);
        }
        diag.setText(s);
    }

    @Override
    protected void onDestroy() {
        rootCheck.stop();
        keyLog.stop();
        trace.stop();
        super.onDestroy();
    }
}
