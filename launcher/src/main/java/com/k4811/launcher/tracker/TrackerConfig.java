package com.k4811.launcher.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import com.k4811.launcher.BuildConfig;

/** Configuração e estado do rastreamento (preferências próprias do launcher; só o car-setting grava o launcher.json). */
final class TrackerConfig {
    final SharedPreferences sp;

    TrackerConfig(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("tracker", Context.MODE_PRIVATE);
    }

    String url() {
        return sp.getString("url", BuildConfig.DEFAULT_URL);
    }

    String token() {
        return sp.getString("token", BuildConfig.DEFAULT_TOKEN);
    }

    /** Identificador no servidor: o do build (CARINFO_DEVICE) ou, na falta, modelo + sufixo aleatório gerado uma vez. */
    String device() {
        String d = sp.getString("device", "");
        if (d.isEmpty()) {
            d = BuildConfig.DEFAULT_DEVICE;
            if (d.isEmpty()) {
                String model = android.os.Build.MODEL == null ? "android" : android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]+", "-");
                d = model + "-" + Long.toHexString(new java.util.Random().nextLong() & 0xffffffL);
            }
            sp.edit().putString("device", d).apply();
        }
        return d;
    }

    int intervalSec() {
        return Math.max(1, sp.getInt("interval", 5));
    }

    int minDistM() {
        return Math.max(0, sp.getInt("dist", 10));
    }

    /** Minutos entre pontos de "estou vivo" com o carro parado; 0 desliga. */
    int heartbeatMin() {
        return Math.max(0, sp.getInt("hb", 10));
    }

    /** Canal de comandos remotos assinados: desligado até o usuário ligar na tela do rastreamento. */
    boolean remote() {
        return sp.getBoolean("remote", false);
    }

    void setStatus(String k, String v) {
        sp.edit().putString("st_" + k, v).apply();
    }

    String status(String k, String def) {
        return sp.getString("st_" + k, def);
    }
}
