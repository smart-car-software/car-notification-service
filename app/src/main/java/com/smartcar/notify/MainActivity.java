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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

/** Settings and the three test cards. */
public class MainActivity extends AppCompatActivity {
    private RootStream rootCheck;
    private TextView wheelStatus;
    private TextView overlay;
    private int testCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        rootCheck = new RootStream(this);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        overlay.setText(Settings.canDrawOverlays(this)
                ? R.string.overlay_granted : R.string.overlay_needed);
        findViewById(R.id.btnOverlay).setEnabled(!Settings.canDrawOverlays(this));
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

    @Override
    protected void onDestroy() {
        rootCheck.stop();
        super.onDestroy();
    }
}
