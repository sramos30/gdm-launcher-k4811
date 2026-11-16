package com.k4811.launcher.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.launcher.BuildConfig;
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

        // Atualização manual: baixa o APK do servidor e abre o instalador do Android (você confirma com um toque).
        final Button update = new Button(this);
        update.setText(R.string.tracker_update);
        update.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadUpdate(update);
            }
        });
        root.addView(update);

        // Envia o estado, os eventos e o logcat do app ao servidor (POST /api/diag), para eu ler daqui.
        final Button logs = new Button(this);
        logs.setText(R.string.tracker_send_logs);
        logs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendLogs(logs);
            }
        });
        root.addView(logs);

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

    private void downloadUpdate(final Button btn) {
        btn.setEnabled(false);
        btn.setText(R.string.tracker_update_downloading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                final java.io.File out = new java.io.File(getExternalFilesDir(null), "launcher-update.apk");
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        cfg.url() + "/dl/launcher?token=" + cfg.token()).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                    java.io.InputStream in = c.getInputStream();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        total += n;
                    }
                    fo.close();
                    in.close();
                    if (total < 100000) throw new java.io.IOException("arquivo pequeno demais (" + total + " B)");
                    out.setReadable(true, false);
                    TrackerLog.log(TrackerActivity.this, "atualização: baixada (" + total + " B)");
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    TrackerLog.log(TrackerActivity.this, "atualização: falhou " + err);
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btn.setEnabled(true);
                        btn.setText(R.string.tracker_update);
                        if (error != null) {
                            Toast.makeText(TrackerActivity.this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            // INSTALL_PACKAGE só casa com instaladores (o VIEW genérico caía no Termux).
                            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                            i.setDataAndType(Uri.fromFile(out), "application/vnd.android.package-archive");
                            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                            i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (getPackageManager().resolveActivity(i, 0) == null) {
                                // Sem instalador padrão nesta central: deixa escolher o app que instala.
                                Intent v = new Intent(Intent.ACTION_VIEW);
                                v.setDataAndType(Uri.fromFile(out), "application/vnd.android.package-archive");
                                i = Intent.createChooser(v, getString(R.string.tracker_update));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                TrackerLog.log(TrackerActivity.this, "atualização: sem instalador padrão, abrindo seletor");
                            }
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(TrackerActivity.this, e.toString(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void sendLogs(final Button btn) {
        btn.setEnabled(false);
        final String head = status.getText().toString() + "\nImpressão: " + Agent.deviceFp(this)
            + "\nVersão: " + BuildConfig.VERSION_NAME + "\n";
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    StringBuilder t = new StringBuilder(head);
                    t.append("\n--- eventos ---\n").append(TrackerLog.recent(TrackerActivity.this, 400));
                    t.append("\n--- logcat ---\n");
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "400"});
                        java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) t.append(line).append('\n');
                        r.close();
                    } catch (Exception e) {
                        t.append("(logcat indisponível: ").append(e).append(")\n");
                    }
                    String text = t.length() > 500000 ? t.substring(t.length() - 500000) : t.toString();
                    org.json.JSONObject body = new org.json.JSONObject().put("device", cfg.device())
                        .put("why", "botão do launcher").put("text", text);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        cfg.url().replaceAll("/+$", "") + "/api/diag").openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Authorization", "Bearer " + cfg.token());
                    java.io.OutputStream os = c.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.close();
                    int code = c.getResponseCode();
                    c.disconnect();
                    msg = code / 100 == 2 ? getString(R.string.tracker_logs_sent) : "HTTP " + code;
                } catch (Exception e) {
                    msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                TrackerLog.log(TrackerActivity.this, "diagnóstico: " + msg);
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btn.setEnabled(true);
                        Toast.makeText(TrackerActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
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
