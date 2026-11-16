package com.k4811.launcher.tracker;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registro de eventos do rastreamento. Só acrescenta ao fim (não perde nada se o processo morrer no meio);
 * ao passar de ~40 KB o arquivo atual vira {@code tracker.old} (o rename substitui o antigo de uma vez).
 */
final class TrackerLog {
    private static final Object LOCK = new Object();
    private static final long MAX = 40 * 1024;

    private TrackerLog() {
    }

    static void log(Context c, String msg) {
        synchronized (LOCK) {
            try {
                File dir = c.getApplicationContext().getFilesDir();
                File f = new File(dir, "tracker.log");
                if (f.length() > MAX) f.renameTo(new File(dir, "tracker.old"));
                FileWriter w = new FileWriter(f, true);
                try {
                    w.write(new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n");
                } finally {
                    w.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** As últimas {@code n} linhas, da mais antiga para a mais nova. */
    static String recent(Context c, int n) {
        synchronized (LOCK) {
            List<String> lines = new ArrayList<>();
            File dir = c.getApplicationContext().getFilesDir();
            for (String name : new String[]{"tracker.old", "tracker.log"}) {
                File f = new File(dir, name);
                if (!f.exists()) continue;
                try {
                    BufferedReader r = new BufferedReader(new FileReader(f));
                    try {
                        String s;
                        while ((s = r.readLine()) != null) lines.add(s);
                    } finally {
                        r.close();
                    }
                } catch (Exception ignored) {
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString().trim();
        }
    }
}
