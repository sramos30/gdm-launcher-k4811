package com.k4811.launcher.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.launcher.R;

/**
 * Estado do rastreamento: serviço, posição, envio, cadastro no servidor e a impressão digital do aparelho
 * (o operador a confere antes de aprovar). Também liga e desliga o canal de comandos remotos assinados.
 */
public final class TrackerActivity extends Activity {
    private TrackerConfig cfg;
    private TextView status;
    private TextView fingerprint;
    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new TrackerConfig(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(getString(R.string.tracker_title), 26, 0xFFFFFFFF);
        root.addView(title);

        status = text("", 18, 0xFFE0E0E0);
        status.setPadding(0, pad / 2, 0, pad / 2);
        status.setTextIsSelectable(true);
        root.addView(status);

        root.addView(text(getString(R.string.tracker_fp), 16, 0xFF9E9E9E));
        fingerprint = text("", 30, 0xFFFFFFFF);
        fingerprint.setTypeface(Typeface.MONOSPACE);
        fingerprint.setTextIsSelectable(true);
        fingerprint.setPadding(0, pad / 4, 0, pad);
        root.addView(fingerprint);

        final CheckBox remote = new CheckBox(this);
        remote.setText(R.string.tracker_remote);
        remote.setTextColor(0xFFFFFFFF);
        remote.setTextSize(18);
        remote.setChecked(cfg.remote());
        remote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!remote.isChecked()) {
                    cfg.sp.edit().putBoolean("remote", false).apply();
                    TrackerLog.log(TrackerActivity.this, "remoto: desligado pelo usuário");
                    return;
                }
                remote.setChecked(false);
                new AlertDialog.Builder(TrackerActivity.this)
                    .setTitle(R.string.tracker_remote_title)
                    .setMessage(R.string.tracker_remote_msg)
                    .setPositiveButton(R.string.tracker_enable, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            cfg.sp.edit().putBoolean("remote", true).apply();
                            remote.setChecked(true);
                            TrackerLog.log(TrackerActivity.this, "remoto: ligado pelo usuário");
                            TrackerService.start(TrackerActivity.this);
                            Agent.ensure(TrackerActivity.this);
                        }
                    })
                    .setNegativeButton(R.string.tracker_cancel, null)
                    .show();
            }
        });
        root.addView(remote);

        TextView log = text("", 13, 0xFF9E9E9E);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTag("log");
        log.setPadding(0, pad, 0, 0);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF121212);
        sv.addView(root);
        setContentView(sv);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private String regText() {
        String r = cfg.status("reg", "none");
        if ("approved".equals(r)) return getString(R.string.tracker_reg_approved);
        if ("pending".equals(r)) return getString(R.string.tracker_reg_pending);
        if ("off".equals(r)) return getString(R.string.tracker_reg_off);
        return getString(R.string.tracker_reg_none);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String err = cfg.status("err", "");
            status.setText(getString(R.string.tracker_service) + ": "
                + getString(TrackerService.running ? R.string.tracker_running : R.string.tracker_stopped)
                + "\n" + getString(R.string.tracker_device) + ": " + cfg.device()
                + "\n" + getString(R.string.tracker_fix) + ": " + cfg.status("fix", "-")
                + "\n" + getString(R.string.tracker_sent) + ": " + cfg.status("sent", "-")
                + "\n" + getString(R.string.tracker_queue) + ": " + cfg.status("queue", "0")
                + (err.isEmpty() ? "" : "\n" + getString(R.string.tracker_error) + ": " + err)
                + "\n" + getString(R.string.tracker_reg) + ": " + (cfg.remote() ? regText() : getString(R.string.tracker_reg_off)));
            String fp = Agent.deviceFp(TrackerActivity.this);
            fingerprint.setText(fp.replaceAll("(.{4})(?=.)", "$1 ").toUpperCase());
            View l = findLogView();
            if (l instanceof TextView) ((TextView) l).setText(TrackerLog.recent(TrackerActivity.this, 12));
            ui.postDelayed(this, 1000);
        }
    };

    private View findLogView() {
        return getWindow().getDecorView().findViewWithTag("log");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }
}
