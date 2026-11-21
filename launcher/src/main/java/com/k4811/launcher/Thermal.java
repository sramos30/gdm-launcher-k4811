package com.k4811.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.k4811.common.Apps;
import com.k4811.launcher.tracker.TrackerLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Temperatura do SoC e proteção contra superaquecimento.
 *
 * Centrais sem ventoinha (ex.: Lelong LE-1619) esquentam até travar o vídeo. Nelas, a partir de {@link #OVERHEAT_C}
 * o launcher pausa a mídia e apaga a tela (o mesmo "tela desligada" do botão da tela inicial). A verificação roda no
 * TrackerService, que fica vivo com qualquer app na frente. Centrais com ventoinha (app do ventilador instalado) não
 * são afetadas: só mostram a temperatura.
 */
public final class Thermal {
    public static final int OVERHEAT_C = 90;
    public static final String EXTRA_OVERHEAT = "com.k4811.launcher.extra.OVERHEAT";
    private static final long RETRIGGER_MS = 5 * 60000L;

    /** Até quando não disparar de novo (elapsedRealtime). A tela inicial também a adia quando o usuário toca para acordar. */
    public static volatile long lockUntil = 0;
    private static Boolean fan;

    private Thermal() {
    }

    /** Maior thermal_zone em °C, ou -1 se o kernel não expõe. Valores acima de 1000 são milésimos de grau. */
    public static int readC() {
        int best = -1;
        for (int i = 0; i < 12; i++) {
            File f = new File("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (!f.canRead()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line == null) continue;
                long v = Long.parseLong(line.trim());
                if (v > 1000) v /= 1000;
                if (v > 0 && v < 150 && v > best) best = (int) v;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /** A central tem o app da ventoinha da NWD instalado? Sem ele, assume que não há ventoinha. */
    public static synchronized boolean fanPresent(Context c) {
        if (fan == null) {
            try {
                c.getPackageManager().getPackageInfo(Apps.FAN, 0);
                fan = true;
            } catch (PackageManager.NameNotFoundException e) {
                fan = false;
            }
        }
        return fan;
    }

    /** Chamado a cada tick do serviço. Sem ventoinha e a ≥ 90 °C: pausa a mídia e leva a tela inicial a apagar a tela. */
    public static void check(Context c) {
        try {
            if (fanPresent(c)) return;
            int t = readC();
            if (t < OVERHEAT_C) return;
            long now = SystemClock.elapsedRealtime();
            if (now < lockUntil) return;
            lockUntil = now + RETRIGGER_MS;
            TrackerLog.log(c, "calor: " + t + "°C (limite " + OVERHEAT_C + "), sem ventoinha: pausando a mídia e apagando a tela");
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            Intent i = new Intent(c, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OVERHEAT, true);
            c.startActivity(i);
        } catch (Throwable e) {
            TrackerLog.log(c, "calor: falha na proteção: " + e);
        }
    }
}
