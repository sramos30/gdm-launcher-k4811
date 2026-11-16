package com.k4811.launcher.tracker;

import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Assinaturas ECDSA P-256 / SHA-256, sem depender do Android (dá para testar na JVM). Base64 próprio porque
 * {@code java.util.Base64} só existe a partir da API 26 e a suíte aceita API 17.
 */
final class Sig {
    private static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Sig() {
    }

    static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 255) << 16 | (i + 1 < d.length ? (d[i + 1] & 255) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 255 : 0);
            sb.append(ALPHA.charAt(n >> 18 & 63)).append(ALPHA.charAt(n >> 12 & 63));
            sb.append(i + 1 < d.length ? ALPHA.charAt(n >> 6 & 63) : '=').append(i + 2 < d.length ? ALPHA.charAt(n & 63) : '=');
        }
        return sb.toString();
    }

    static byte[] unb64(String s) {
        s = s.replaceAll("[^A-Za-z0-9+/]", "");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            buf = buf << 6 | ALPHA.indexOf(s.charAt(i));
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                o.write(buf >> bits & 255);
            }
        }
        return o.toByteArray();
    }

    static String hex(int nBytes) {
        byte[] r = new byte[nBytes];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Impressão digital = 16 primeiros hex do SHA-256 do SubjectPublicKeyInfo (igual ao openssl e ao servidor). */
    static String fp(PublicKey k) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(k.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
        return sb.toString();
    }

    static PublicKey parsePem(String pem) throws Exception {
        String body = pem.replaceAll("-----[A-Z ]+-----", "");
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(body)));
    }

    static PublicKey pubFromB64(String b) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(b)));
    }

    static PrivateKey privFromB64(String b) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(unb64(b)));
    }

    static KeyPair generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static boolean verify(PublicKey k, String payload, String sigB64) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(k);
            s.update(payload.getBytes(UTF8));
            return s.verify(unb64(sigB64));
        } catch (Throwable e) {
            return false;
        }
    }

    static String sign(PrivateKey k, String payload) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(payload.getBytes(UTF8));
        return b64(s.sign());
    }
}
