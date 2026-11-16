package com.k4811.launcher.tracker;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Canal de comandos remotos ASSINADOS. Só roda se o usuário ligou "comandos remotos" na tela do rastreamento.
 *
 * <ul>
 * <li>A chave do aparelho (EC P-256) é gerada uma vez e guardada nas preferências do app. A pública viaja UMA vez, no
 * pedido de cadastro ({@code /api/agent/enroll}), assinado com a própria privada. Depois cada contato
 * ({@code /api/agent/poll}) só é assinado, sem reenviar a chave.</li>
 * <li>Só executa pacote assinado por um operador embutido em {@code assets/operators/*.pem}, endereçado a ESTE aparelho
 * (id e impressão digital), dentro da validade e nunca visto antes. A resposta volta assinada pelo aparelho.</li>
 * <li>O estado do cadastro fica em {@code st_reg}: none, pending, approved ou off.</li>
 * </ul>
 */
final class Agent implements Runnable {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static Thread thread;
    private static KeyPair devKey;
    private final Context ctx;
    private String lastReg = "";

    private Agent(Context c) {
        ctx = c.getApplicationContext();
    }

    /** Garante uma única thread rodando (chamado a cada início do serviço). */
    static synchronized void ensure(Context c) {
        if (!new TrackerConfig(c).remote()) return;
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(new Agent(c), "tracker-agent");
        thread.setDaemon(true);
        thread.start();
    }

    /** Chave do aparelho: gerada uma vez e guardada na área privada do app. */
    static synchronized KeyPair deviceKey(Context c) throws Exception {
        if (devKey != null) return devKey;
        TrackerConfig cfg = new TrackerConfig(c);
        String pr = cfg.sp.getString("dk_priv", ""), pu = cfg.sp.getString("dk_pub", "");
        try {
            if (!pr.isEmpty()) devKey = new KeyPair(Sig.pubFromB64(pu), Sig.privFromB64(pr));
        } catch (Exception ignored) {
        }
        if (devKey == null) {
            devKey = Sig.generate();
            cfg.sp.edit().putString("dk_priv", Sig.b64(devKey.getPrivate().getEncoded()))
                .putString("dk_pub", Sig.b64(devKey.getPublic().getEncoded())).apply();
        }
        return devKey;
    }

    static String deviceFp(Context c) {
        try {
            return Sig.fp(deviceKey(c).getPublic());
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Chaves públicas dos operadores confiáveis, por impressão digital. */
    static Map<String, PublicKey> operators(Context c) {
        Map<String, PublicKey> m = new HashMap<>();
        try {
            String[] names = c.getAssets().list("operators");
            if (names == null) return m;
            for (String n : names) {
                if (!n.endsWith(".pem")) continue;
                try {
                    InputStream in = c.getAssets().open("operators/" + n);
                    try {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] b = new byte[2048];
                        int k;
                        while ((k = in.read(b)) > 0) o.write(b, 0, k);
                        PublicKey pk = Sig.parsePem(o.toString("UTF-8"));
                        m.put(Sig.fp(pk), pk);
                    } finally {
                        in.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private void reg(TrackerConfig cfg, String code) {
        cfg.setStatus("reg", code);
        if (!code.equals(lastReg)) {
            lastReg = code;
            TrackerLog.log(ctx, "remoto: cadastro " + code);
        }
    }

    private JSONObject signed(TrackerConfig cfg, String t, String extraKey, String extraVal) throws Exception {
        JSONObject pl = new JSONObject().put("v", 1).put("t", t).put("device", cfg.device())
            .put("nonce", Sig.hex(16)).put("iat", System.currentTimeMillis() / 1000);
        if (extraKey != null) pl.put(extraKey, extraVal);
        String payload = pl.toString();
        return new JSONObject().put("device", cfg.device()).put("payload", payload)
            .put("sig", Sig.sign(deviceKey(ctx).getPrivate(), payload));
    }

    @Override
    public void run() {
        Map<String, PublicKey> ops = operators(ctx);
        TrackerLog.log(ctx, "remoto: canal ATIVO (" + ops.size() + " operadores confiáveis, impressão do aparelho " + deviceFp(ctx) + ")");
        int fails = 0;
        while (true) {
            TrackerConfig cfg = new TrackerConfig(ctx);
            if (!cfg.remote()) {
                reg(cfg, "off");
                TrackerLog.log(ctx, "remoto: canal desligado");
                return;
            }
            try {
                // a chave pública NÃO viaja a cada contato: o aparelho só assina o contato
                JSONObject r = post(cfg, "/api/agent/poll", signed(cfg, "poll", null, null), 35000);
                fails = 0;
                String st = r == null ? "" : r.optString("status", "");
                if ("unenrolled".equals(st)) {
                    JSONObject e = post(cfg, "/api/agent/enroll",
                        signed(cfg, "enroll", "pub", Sig.b64(deviceKey(ctx).getPublic().getEncoded())), 20000);
                    reg(cfg, e != null && "approved".equals(e.optString("status", "")) ? "approved" : "pending");
                    pause(5000);
                } else if ("pending".equals(st)) {
                    reg(cfg, "pending");
                    pause(20000);
                } else {
                    reg(cfg, "approved");
                    if (r != null && r.has("id")) handle(cfg, ops, r);
                }
            } catch (Throwable e) {
                if (fails++ == 0) TrackerLog.log(ctx, "remoto: falha no canal: " + e);
                pause(Math.min(60000, 5000L * (1 << Math.min(fails, 4))));
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Devolve o motivo da recusa, ou null se o pacote é válido. */
    private String validate(TrackerConfig cfg, Map<String, PublicKey> ops, String payload, String sig, JSONObject d) {
        try {
            PublicKey k = ops.get(d.optString("kid"));
            if (k == null) return "operador desconhecido (kid " + d.optString("kid") + ")";
            if (!Sig.verify(k, payload, sig)) return "assinatura inválida";
            if (d.optInt("v") != 1 || !"cmd".equals(d.optString("t"))) return "tipo de pacote inesperado";
            if (!cfg.device().equals(d.optString("device"))) return "pacote endereçado a outro aparelho";
            if (!deviceFp(ctx).equals(d.optString("dfp"))) return "impressão digital do aparelho não confere";
            long now = System.currentTimeMillis() / 1000, iat = d.optLong("iat"), exp = d.optLong("exp");
            if (exp - iat > 900 || now > exp || iat > now + 120)
                return "fora da validade (relógio do aparelho: " + now + ", pacote iat=" + iat + " exp=" + exp + ")";
            String nonce = d.optString("nonce");
            if (nonce.length() < 8) return "nonce inválido";
            String seen = cfg.sp.getString("nonces", "");
            for (String s : seen.split(",")) if (s.equals(nonce)) return "pacote repetido";
            String[] all = (seen.isEmpty() ? nonce : seen + "," + nonce).split(",");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, all.length - 300); i < all.length; i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(all[i]);
            }
            cfg.sp.edit().putString("nonces", sb.toString()).commit();   // grava ANTES de executar
            return null;
        } catch (Throwable e) {
            return "erro na validação: " + e;
        }
    }

    private void handle(TrackerConfig cfg, Map<String, PublicKey> ops, JSONObject env) throws Exception {
        int id = env.getInt("id");
        String payload = env.getString("payload"), sig = env.getString("sig");
        JSONObject d;
        try {
            d = new JSONObject(payload);
        } catch (Exception e) {
            reject(cfg, id, "pacote ilegível");
            return;
        }
        String why = validate(cfg, ops, payload, sig, d);
        if (why != null) {
            TrackerLog.log(ctx, "remoto: pacote #" + id + " RECUSADO: " + why);
            reject(cfg, id, why);
            return;
        }
        String sh = d.getString("cmd");
        int timeout = Math.max(1, Math.min(300, d.optInt("timeout", 30)));
        TrackerLog.log(ctx, "remoto: comando #" + id + " (assinado por " + d.optString("kid") + "): "
            + (sh.length() > 80 ? sh.substring(0, 80) + "..." : sh));
        int exit = -1;
        boolean timedOut = false;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            final Process p = new ProcessBuilder("sh", "-c", sh).redirectErrorStream(true).directory(ctx.getFilesDir()).start();
            p.getOutputStream().close();
            Thread rd = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = p.getInputStream();
                        try {
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                synchronized (out) {
                                    if (out.size() < 200000) out.write(b, 0, Math.min(n, 200000 - out.size()));
                                }
                            }
                        } finally {
                            in.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            rd.start();
            long end = System.currentTimeMillis() + timeout * 1000L;
            while (true) {
                try {
                    exit = p.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    // ainda rodando
                }
                if (System.currentTimeMillis() > end) {
                    timedOut = true;
                    p.destroy();
                    break;
                }
                Thread.sleep(100);
            }
            rd.join(2000);
        } catch (Throwable e) {
            synchronized (out) {
                out.write(("erro ao executar: " + e + "\n").getBytes(UTF8));
            }
        }
        String text;
        synchronized (out) {
            text = new String(out.toByteArray(), UTF8);
        }
        String res = new JSONObject().put("v", 1).put("t", "res").put("device", cfg.device()).put("nonce", d.getString("nonce"))
            .put("exit", exit).put("timed_out", timedOut).put("out", text).toString();
        post(cfg, "/api/agent/result", new JSONObject().put("device", cfg.device()).put("id", id).put("payload", res)
            .put("sig", Sig.sign(deviceKey(ctx).getPrivate(), res)), 20000);
        TrackerLog.log(ctx, "remoto: comando #" + id + " concluído (saída " + exit + (timedOut ? ", tempo esgotado" : "") + ")");
    }

    private void reject(TrackerConfig cfg, int id, String why) throws Exception {
        post(cfg, "/api/agent/result", new JSONObject().put("device", cfg.device()).put("id", id).put("reject", why), 20000);
    }

    private static JSONObject post(TrackerConfig cfg, String path, JSONObject body, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.url().replaceAll("/+$", "") + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
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
        if (code / 100 != 2) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            in.close();
        }
        c.disconnect();
        String s = o.toString("UTF-8").trim();
        return s.isEmpty() ? null : new JSONObject(s);
    }
}
