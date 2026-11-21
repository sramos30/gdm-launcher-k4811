package com.k4811.launcher.tracker;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.k4811.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Rastreamento: envia a posição do carro ao servidor (carinfo). Mesma regra do app CarInfo:
 * verifica a cada N segundos, só enfileira se andou pelo menos X metros (ignorando precisão pior que 50 m),
 * manda um ponto de "estou vivo" a cada Y minutos parado, guarda numa fila em disco e reenvia quando a rede volta.
 *
 * Iniciado pelo {@code LauncherApp}: como o launcher é o app de HOME, o sistema o inicia no boot e o reinicia se morrer,
 * e o firmware da central não o mata ao dormir.
 */
public final class TrackerService extends Service implements LocationListener {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_QUEUE = 20000;
    private static final int BATCH = 500;
    private static final float BAD_ACCURACY = 50f;
    private static final long FLUSH_MS = 15000;
    /**
     * Relógio de parede abaixo disto não é confiável: algumas centrais (Lelong) perdem a hora ao desligar e voltam
     * para 01/01/2022 até o NTP acertar. É uma data antes de qualquer build real; suba se um dia ficar velha demais.
     * (2026-11-01 00:00 UTC)
     */
    private static final long MIN_TRUSTED_MS = 1793491200000L;

    /** Serviço de pé neste processo. */
    static volatile boolean running = false;

    private TrackerConfig cfg;
    private LocationManager lm;
    private HandlerThread ht;
    private Handler h;
    private PowerManager.WakeLock wl;
    private File queueFile;
    private Location gpsFix, netFix;
    private Location lastSent;
    private long lastSentEl = -1;       // SystemClock.elapsedRealtime() do último ponto (-1 = nenhum ainda)
    private long lastFlush = 0;
    private long startedEl = 0;
    /** Âncora de hora UTC vinda do GPS: utc = anchorUtc + (elapsedRealtime - anchorEl). Independe do relógio de parede. */
    private long anchorUtc = 0, anchorEl = 0;
    private boolean warnedNoTime = false;
    private boolean loggedFirstFix = false;
    private boolean started = false;
    private String lastNotification = "";

    /** Inicia (ou confirma) o serviço. Seguro de chamar várias vezes. */
    public static void start(Context c) {
        try {
            c.startService(new Intent(c, TrackerService.class));
        } catch (Throwable e) {
            TrackerLog.log(c, "serviço: erro ao iniciar: " + e);
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        cfg = new TrackerConfig(this);
        long al = cfg.sp.getLong("alive", 0);
        if (al > 0) {
            TrackerLog.log(this, "serviço anterior morreu sem aviso (último sinal "
                + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new Date(al)) + ")");
        }
        TrackerLog.log(this, "serviço: onCreate");
        queueFile = new File(getFilesDir(), "queue.jsonl");
        ht = new HandlerThread("tracker");
        ht.start();
        h = new Handler(ht.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification("…"));
        if (!started) {
            started = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracker:svc");
            wl.acquire();
            startedEl = SystemClock.elapsedRealtime();
            TrackerLog.log(this, "serviço iniciado (dispositivo " + cfg.device() + ")");
            cfg.setStatus("started", DateFormat.getDateTimeInstance().format(new Date()));
            registerListeners();
            h.post(tick);
        }
        Agent.ensure(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        cfg.sp.edit().putLong("alive", 0).apply();
        TrackerLog.log(this, "serviço destruído");
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        if (h != null) h.removeCallbacksAndMessages(null);
        if (ht != null) ht.quit();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    private void registerListeners() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        long ms = cfg.intervalSec() * 1000L;
        try {
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0, this, ht.getLooper());
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, ms, 0, this, ht.getLooper());
        } catch (SecurityException e) {
            cfg.setStatus("err", "sem permissão de localização");
            TrackerLog.log(this, "sem permissão de localização");
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            gpsFix = l;
            if (l.getTime() >= MIN_TRUSTED_MS) {      // hora UTC dos satélites
                anchorUtc = l.getTime();
                anchorEl = elapsedOf(l);
            }
        } else netFix = l;
    }

    @Override
    public void onStatusChanged(String p, int s, Bundle e) {
    }

    @Override
    public void onProviderEnabled(String p) {
    }

    @Override
    public void onProviderDisabled(String p) {
    }

    /** Instante do fix em SystemClock.elapsedRealtime() (ms), que não muda quando o relógio de parede é acertado. */
    private static long elapsedOf(Location l) {
        long ns = l.getElapsedRealtimeNanos();
        return ns > 0 ? ns / 1000000L : SystemClock.elapsedRealtime() - (System.currentTimeMillis() - l.getTime());
    }

    private static long ageMs(Location l) {
        return SystemClock.elapsedRealtime() - elapsedOf(l);
    }

    /** Hora UTC confiável agora, ou 0 se não houver (relógio de parede ruim e nenhum fix GPS desde o boot). */
    private long trustedNow() {
        long wall = System.currentTimeMillis();
        if (wall >= MIN_TRUSTED_MS) return wall;
        if (anchorUtc > 0) return anchorUtc + (SystemClock.elapsedRealtime() - anchorEl);
        return 0;
    }

    /** Hora do ponto: a do próprio fix GPS (satélites) ou a hora confiável; 0 = sem hora confiável. */
    private long pointTime(Location fix) {
        if (fix != null && LocationManager.GPS_PROVIDER.equals(fix.getProvider()) && fix.getTime() >= MIN_TRUSTED_MS)
            return fix.getTime();
        return trustedNow();
    }

    private Location bestFix() {
        long maxAge = Math.max(30000, cfg.intervalSec() * 3000L);
        if (gpsFix != null && ageMs(gpsFix) <= maxAge) return gpsFix;
        if (netFix != null && ageMs(netFix) <= maxAge) return netFix;
        return null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            cfg.sp.edit().putLong("alive", System.currentTimeMillis()).apply();
            try {
                evaluate();
            } catch (Throwable t) {
                cfg.setStatus("err", "tick: " + t);
            }
            com.k4811.launcher.Thermal.check(TrackerService.this);
            h.postDelayed(this, cfg.intervalSec() * 1000L);
        }
    };

    private void evaluate() throws Exception {
        long now = SystemClock.elapsedRealtime();   // só para intervalos; a hora dos pontos vem de pointTime()
        Location fix = bestFix();
        boolean enqueue = false;
        if (fix != null && !loggedFirstFix) {
            loggedFirstFix = true;
            TrackerLog.log(this, "primeiro fix: " + fix.getProvider());
        }
        if (fix != null) {
            if (lastSent == null) {
                enqueue = true;                       // primeiro ponto: aceita qualquer precisão
            } else if ((!fix.hasAccuracy() || fix.getAccuracy() <= BAD_ACCURACY)
                && lastSent.distanceTo(fix) >= cfg.minDistM()) {
                enqueue = true;                       // movimento só conta com precisão razoável
            }
        }
        int hb = cfg.heartbeatMin();
        boolean waitingFirstFix = fix == null && lastSent == null && now - startedEl < 45000;   // espera até 45 s pelo 1º fix
        boolean heartbeat = !enqueue && !waitingFirstFix && hb > 0 && (lastSentEl < 0 || now - lastSentEl >= hb * 60000L);

        if (enqueue || heartbeat) {
            long ts = pointTime(fix);
            if (ts > 0) {
                append(toJson(fix, ts).toString());
                lastSentEl = now;
                if (enqueue) lastSent = fix;
            } else if (!warnedNoTime) {
                warnedNoTime = true;       // relógio de parede ruim e ainda sem fix GPS: um ponto com data errada é pior que nenhum
                TrackerLog.log(this, "sem hora confiável (relógio do sistema antes de 2026 e sem fix GPS): ponto não enfileirado");
            }
        }
        cfg.setStatus("fix", fix == null ? "-" :
            String.format("%s %.5f, %.5f ±%.0fm", fix.getProvider(), fix.getLatitude(), fix.getLongitude(),
                fix.hasAccuracy() ? fix.getAccuracy() : -1));
        if (lastFlush == 0 || now - lastFlush >= FLUSH_MS) {
            lastFlush = now;
            flush();
        }
        cfg.setStatus("queue", String.valueOf(queueSize()));
        updateNotification();
    }

    private JSONObject toJson(Location l, long ts) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", ts);
        if (l != null) {
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            if (l.hasAltitude()) o.put("alt", l.getAltitude());
            if (l.hasSpeed()) o.put("speed", l.getSpeed());
            if (l.hasBearing()) o.put("bearing", l.getBearing());
            if (l.hasAccuracy()) o.put("accuracy", l.getAccuracy());
            o.put("provider", l.getProvider());
            Bundle ex = l.getExtras();
            if (ex != null && ex.containsKey("satellites")) o.put("sats", ex.getInt("satellites"));
        } else {
            o.put("provider", "none");
        }
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && sc > 0) o.put("battery", lvl * 100 / sc);
        }
        return o;
    }

    // ---- fila em disco ----
    private synchronized void append(String line) throws Exception {
        List<String> all = readAll();
        all.add(line);
        if (all.size() > MAX_QUEUE) all = new ArrayList<>(all.subList(all.size() - MAX_QUEUE, all.size()));
        writeAll(all);
    }

    private synchronized List<String> readAll() throws Exception {
        List<String> out = new ArrayList<>();
        if (!queueFile.exists()) return out;
        BufferedReader r = new BufferedReader(new FileReader(queueFile));
        try {
            String s;
            while ((s = r.readLine()) != null) if (!s.isEmpty()) out.add(s);
        } finally {
            r.close();
        }
        return out;
    }

    private synchronized void writeAll(List<String> lines) throws Exception {
        File tmp = new File(queueFile.getPath() + ".tmp");
        FileWriter w = new FileWriter(tmp);
        try {
            for (String s : lines) {
                w.write(s);
                w.write('\n');
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(queueFile)) throw new IOException("rename");
    }

    private int queueSize() {
        try {
            return readAll().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private void flush() {
        if (cfg.token().isEmpty() || cfg.url().isEmpty()) {
            cfg.setStatus("err", "sem URL ou token do servidor neste build");
            return;
        }
        try {
            while (true) {
                List<String> all = readAll();
                if (all.isEmpty()) return;
                int n = Math.min(BATCH, all.size());
                JSONArray arr = new JSONArray();
                for (int i = 0; i < n; i++) arr.put(new JSONObject(all.get(i)));
                JSONObject body = new JSONObject();
                body.put("device", cfg.device());
                body.put("positions", arr);

                HttpURLConnection c = (HttpURLConnection) new URL(cfg.url().replaceAll("/+$", "") + "/api/positions").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + cfg.token());
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.toString().getBytes(UTF8));
                } finally {
                    os.close();
                }
                int code = c.getResponseCode();
                c.disconnect();
                if (code / 100 == 2) {
                    synchronized (this) {
                        List<String> now = readAll();
                        writeAll(now.subList(Math.min(n, now.size()), now.size()));
                    }
                    cfg.setStatus("sent", DateFormat.getDateTimeInstance().format(new Date()) + " (" + n + ")");
                    cfg.setStatus("err", "");
                } else if (code == 400 || code == 422) {
                    // lote inválido: descarta para não travar a fila
                    synchronized (this) {
                        List<String> now = readAll();
                        writeAll(now.subList(Math.min(n, now.size()), now.size()));
                    }
                    cfg.setStatus("err", "HTTP " + code + " (lote descartado)");
                } else {
                    cfg.setStatus("err", "HTTP " + code);
                    return;
                }
            }
        } catch (Exception e) {
            cfg.setStatus("err", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---- notificação do serviço em primeiro plano ----
    private Notification notification(String text) {
        return new Notification.Builder(this)
            .setContentTitle(getString(R.string.tracker_title)).setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build();
    }

    private void updateNotification() {
        String t = cfg.status("fix", "-") + " | " + cfg.status("queue", "0");
        if (t.equals(lastNotification)) return;
        lastNotification = t;
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification(t));
    }
}
