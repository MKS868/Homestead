/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Switches the input of the LG TV the stick is plugged into, over the home network.
 *
 * A Fire TV stick is an HDMI source: Fire OS lets only its own system apps send HDMI-CEC,
 * and CEC has no way for a source to pick another source anyway. An LG webOS TV takes the
 * same request its phone app sends, on a WebSocket at port 3001: the TV is found with SSDP,
 * asks once on screen whether to allow Homestead, and from then on knows us by the key it
 * handed out. The TV's address and that key are kept in Prefs; the key is never logged.
 */
final class LgTv {

    private static final String TAG = "Homestead";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int PORT = 3001;
    private static final String SSDP_ST = "urn:lge-com:service:webos-second-screen:1";
    private static final long SSDP_WAIT_MS = 3000L;
    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 5000;
    /** How long the "allow this device?" prompt waits for an answer on the TV. */
    private static final long PAIR_WAIT_MS = 60000L;
    /** The TV only ever sends short replies; anything bigger is not the TV. */
    private static final int MAX_FRAME = 1 << 20;
    /**
     * SHA-256 of the public key of "LGE SSG Intermediate CA", which signs the certificate
     * LG webOS TVs serve on port 3001 (read from the TV, valid to 2034). The TV's own
     * certificate is not issued for its address, so the chain is checked against this key
     * instead of the system's CA list.
     */
    private static final String LG_CA_PIN = "0bwx0Jl9BLSneUdq+A7LNIlHeLWjd8USw/f9xnnoOj8=";
    /** Only what switching inputs needs. */
    private static final String[] PERMISSIONS = { "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST", "LAUNCH" };

    /** One switch at a time: a second press while the TV is asking would pair twice. */
    private static boolean busy;

    private LgTv() { }

    /**
     * Switch the TV to inputId ("HDMI_4") in the background. Progress and failures show as
     * toasts, since this also runs from the remote's button with no screen of ours up.
     */
    static void switchInput(Context context, final String inputId, final String label) {
        final Context c = context.getApplicationContext();
        synchronized (LgTv.class) {
            if (busy) return;
            busy = true;
        }
        Log.i(TAG, "switching the TV to " + inputId);
        new Thread(new Runnable() {
            public void run() {
                try {
                    connectAndSwitch(c, inputId);
                    Log.i(TAG, "TV switched to " + inputId);
                } catch (Throwable t) {
                    Log.w(TAG, "TV input switch failed: " + t);
                    toast(c, c.getString(R.string.tv_switch_failed, label), Toast.LENGTH_LONG);
                } finally {
                    synchronized (LgTv.class) { busy = false; }
                }
            }
        }, "homestead-lgtv").start();
    }

    private static void connectAndSwitch(Context c, String inputId) throws Exception {
        String host = Prefs.lgHost(c);
        Ws ws = null;
        if (host != null) {
            try { ws = Ws.open(host); } catch (IOException e) { Log.i(TAG, "TV not at " + host + ": " + e); }
        }
        if (ws == null) {
            host = discover();
            if (host == null) throw new IOException("no LG TV found on the network");
            Prefs.setLgHost(c, host);
            ws = Ws.open(host);
        }
        try {
            register(c, ws);
            JSONObject r = request(ws, "ssap://tv/switchInput", new JSONObject().put("inputId", inputId));
            if (r.optBoolean("returnValue", false)) return;
            // Some firmware answers switchInput with an error but opens the input as an app.
            String app = "com.webos.app." + inputId.toLowerCase(java.util.Locale.US).replace("_", "");
            Log.i(TAG, "switchInput refused (" + r.optString("errorText") + "), launching " + app);
            r = request(ws, "ssap://system.launcher/launch", new JSONObject().put("id", app));
            if (!r.optBoolean("returnValue", false)) throw new IOException("TV refused: " + r.optString("errorText"));
        } finally {
            ws.close();
        }
    }

    /** Identify to the TV with the stored key, or ask it to allow us and keep the key it gives. */
    private static void register(Context c, Ws ws) throws Exception {
        JSONObject manifest = new JSONObject()
                .put("manifestVersion", 1)
                .put("appVersion", "1.0")
                .put("permissions", new JSONArray(java.util.Arrays.asList(PERMISSIONS)));
        JSONObject payload = new JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put("manifest", manifest);
        String key = Prefs.lgKey(c);
        if (key != null) payload.put("client-key", key);
        ws.send(new JSONObject().put("type", "register").put("id", "reg").put("payload", payload).toString());

        long until = System.currentTimeMillis() + PAIR_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            JSONObject m = ws.readJson(until);
            String type = m.optString("type");
            if ("registered".equals(type)) {
                String k = m.optJSONObject("payload") == null ? null
                        : m.optJSONObject("payload").optString("client-key", null);
                if (k != null && !k.equals(key)) Prefs.setLgKey(c, k);
                return;
            }
            if ("error".equals(type)) {
                // A key the TV no longer knows (it was reset, or the user removed us): forget it.
                if (key != null) Prefs.setLgKey(c, null);
                throw new IOException("TV refused pairing: " + m.optString("error"));
            }
            JSONObject p = m.optJSONObject("payload");
            if ("response".equals(type) && p != null && "PROMPT".equals(p.optString("pairingType"))) {
                Log.i(TAG, "TV is asking whether to allow Homestead");
                toast(c, c.getString(R.string.tv_allow_prompt), Toast.LENGTH_LONG);
            }
        }
        throw new IOException("the TV's prompt was not answered");
    }

    private static int nextId = 0;

    private static JSONObject request(Ws ws, String uri, JSONObject payload) throws Exception {
        String id = "q" + (++nextId);
        ws.send(new JSONObject().put("type", "request").put("id", id)
                .put("uri", uri).put("payload", payload).toString());
        long until = System.currentTimeMillis() + READ_MS;
        while (true) {
            JSONObject m = ws.readJson(until);
            if (!id.equals(m.optString("id"))) continue;
            if ("error".equals(m.optString("type"))) {
                return new JSONObject().put("returnValue", false).put("errorText", m.optString("error"));
            }
            JSONObject p = m.optJSONObject("payload");
            return p != null ? p : new JSONObject();
        }
    }

    /** The address of the first LG webOS TV that answers an SSDP search, or null. */
    static String discover() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(500);
            byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + SSDP_ST + "\r\n\r\n").getBytes(UTF8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(q, q.length, group, 1900));
            s.send(new DatagramPacket(q, q.length, group, 1900));
            byte[] buf = new byte[2048];
            long until = System.currentTimeMillis() + SSDP_WAIT_MS;
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String reply = new String(p.getData(), 0, p.getLength(), UTF8);
                if (reply.contains(SSDP_ST)) {
                    String host = p.getAddress().getHostAddress();
                    Log.i(TAG, "found an LG TV at " + host);
                    return host;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TV search failed: " + t);
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private static void toast(final Context c, final String text, final int length) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() { Toast.makeText(c, text, length).show(); }
        });
    }

    /** Accepts the TV's certificate only if LG's intermediate CA (pinned above) signed it. */
    private static final class LgTrust implements X509TrustManager {
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length < 2) throw new CertificateException("no CA in chain");
            try {
                byte[] spki = chain[1].getPublicKey().getEncoded();
                String pin = Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(spki), Base64.NO_WRAP);
                if (!LG_CA_PIN.equals(pin)) throw new CertificateException("not LG's CA");
                chain[0].verify(chain[1].getPublicKey());
                chain[0].checkValidity();
                chain[1].checkValidity();
            } catch (CertificateException e) {
                throw e;
            } catch (Exception e) {
                throw new CertificateException(e);
            }
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("not a server");
        }

        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** The little of a WebSocket client (RFC 6455) the TV's API needs: text in and out. */
    private static final class Ws {
        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private final SSLSocket sock;
        private final InputStream in;
        private final OutputStream out;
        private final SecureRandom rnd = new SecureRandom();

        private Ws(SSLSocket sock) throws IOException {
            this.sock = sock;
            this.in = sock.getInputStream();
            this.out = sock.getOutputStream();
        }

        static Ws open(String host) throws Exception {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[] { new LgTrust() }, null);
            SSLSocket s = (SSLSocket) tls.getSocketFactory().createSocket();
            try {
                s.connect(new InetSocketAddress(host, PORT), CONNECT_MS);
                s.setSoTimeout(READ_MS);
                s.startHandshake();
                Ws ws = new Ws(s);
                ws.upgrade(host);
                return ws;
            } catch (Exception e) {
                try { s.close(); } catch (IOException ignored) { }
                throw e;
            }
        }

        private void upgrade(String host) throws Exception {
            byte[] nonce = new byte[16];
            rnd.nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            out.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + PORT + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(UTF8));
            out.flush();
            String head = readHead();
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new IOException("no WebSocket: " + head.split("\r\n")[0]);
            }
            String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(UTF8)), Base64.NO_WRAP);
            if (!head.contains(accept)) throw new IOException("bad WebSocket accept");
        }

        private String readHead() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (b.size() < 8192) {
                int x = in.read();
                if (x < 0) throw new IOException("closed during upgrade");
                b.write(x);
                byte[] a = b.toByteArray();
                int n = a.length;
                if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                    return new String(a, UTF8);
                }
            }
            throw new IOException("upgrade reply too long");
        }

        void send(String text) throws IOException {
            frame(0x1, text.getBytes(UTF8));
        }

        /** A client's frames are always masked. */
        private void frame(int opcode, byte[] data) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
            f.write(0x80 | opcode);
            int n = data.length;
            if (n < 126) {
                f.write(0x80 | n);
            } else if (n < 65536) {
                f.write(0x80 | 126);
                f.write(n >> 8);
                f.write(n);
            } else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) n >> (8 * i)));
            }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            f.write(mask, 0, 4);
            for (int i = 0; i < n; i++) f.write(data[i] ^ mask[i & 3]);
            synchronized (out) {
                out.write(f.toByteArray());
                out.flush();
            }
        }

        /** The next text message as JSON, answering pings on the way; until is a wall-clock deadline. */
        JSONObject readJson(long until) throws Exception {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) throw new SocketTimeoutException("TV did not answer");
                sock.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                int b0 = readByte(), b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len < 0 || len > MAX_FRAME || msg.size() + len > MAX_FRAME) {
                    throw new IOException("frame too large");
                }
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] data = new byte[(int) len];
                readFully(data);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 0x8) throw new IOException("TV closed the connection");
                if (op == 0x9) { frame(0xA, data); continue; }
                if (op == 0xA) continue;
                msg.write(data, 0, data.length);
                if (fin) return new JSONObject(new String(msg.toByteArray(), UTF8));
            }
        }

        private int readByte() throws IOException {
            int x = in.read();
            if (x < 0) throw new IOException("TV closed the connection");
            return x;
        }

        private void readFully(byte[] a) throws IOException {
            int off = 0;
            while (off < a.length) {
                int n = in.read(a, off, a.length - off);
                if (n < 0) throw new IOException("TV closed the connection");
                off += n;
            }
        }

        void close() {
            try { frame(0x8, new byte[0]); } catch (Throwable ignored) { }
            try { sock.close(); } catch (Throwable ignored) { }
        }
    }
}
