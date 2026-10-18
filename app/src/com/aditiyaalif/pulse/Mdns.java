// language: Java, file: Mdns.java, target: Android 16 (API 36)
// Discover the device's OWN wireless-debugging ports over mDNS, so the user
// never reads an IP or a port off another screen. Android advertises:
//
//   _adb-tls-pairing._tcp.local   while "Pair with code" is open  -> pairing port
//   _adb-tls-connect._tcp.local   once paired                     -> connect port
//
// A WifiManager.MulticastLock must be held or the stack drops incoming multicast
// while the screen is on. The port is only advertised while the relevant screen
// is open, so this runs at the moment of pairing/connecting, never cached.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Mdns {
    static final String PAIRING = "_adb-tls-pairing._tcp.local";
    static final String CONNECT = "_adb-tls-connect._tcp.local";
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;

    static final class Found {
        final int port;
        final String name;
        Found(int port, String name) { this.port = port; this.name = name; }
    }

    private Mdns() { }

    /** Every usable answer for a service type, de-duplicated by port. */
    static List<Found> discover(Context ctx, String service, long timeoutMs) {
        List<Found> out = new ArrayList<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        MulticastSocket sock = null;
        try {
            if (wm != null) {
                lock = wm.createMulticastLock("pulse-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            sock = new MulticastSocket(PORT);
            sock.setTimeToLive(4);
            sock.setSoTimeout(500);
            InetAddress group = InetAddress.getByName(GROUP);
            try { sock.joinGroup(group); }
            catch (Throwable t) { Diag.log("mdns joinGroup failed", t); }

            byte[] q = query(service);
            for (int i = 0; i < 3; i++) {
                try { sock.send(new DatagramPacket(q, q.length, group, PORT)); }
                catch (Throwable ignored) { }
                try { Thread.sleep(120); } catch (InterruptedException ignored) { }
            }

            Map<String, Integer> srvPort = new HashMap<>();
            List<String> instances = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try { sock.receive(pkt); }
                catch (java.net.SocketTimeoutException e) { continue; }
                catch (Throwable t) { break; }
                parse(pkt.getData(), pkt.getLength(), service, instances, srvPort);
            }

            Set<Integer> seen = new HashSet<>();
            for (String inst : instances) {
                Integer port = srvPort.get(inst);
                if (port == null || port <= 0) continue;
                if (seen.add(port)) out.add(new Found(port, inst));
            }
            Diag.log("mdns " + service + " -> " + out.size() + " answer(s)");
            return out;
        } catch (Throwable t) {
            Diag.log("mdns discovery failed", t);
            return out;
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) { }
            if (lock != null && lock.isHeld()) {
                try { lock.release(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------- DNS wire

    private static byte[] query(String service) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        short16(b, 0); short16(b, 0);
        short16(b, 1); short16(b, 0); short16(b, 0); short16(b, 0);
        for (String label : service.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] lb = label.getBytes(StandardCharsets.UTF_8);
            b.write(lb.length);
            b.write(lb, 0, lb.length);
        }
        b.write(0);
        short16(b, 12); short16(b, 1);
        return b.toByteArray();
    }

    private static void short16(ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static void parse(byte[] d, int len, String service,
                              List<String> instances, Map<String, Integer> srvPort) {
        try {
            if (len < 12) return;
            int qd = u16(d, 4), an = u16(d, 6), ns = u16(d, 8), ar = u16(d, 10);
            int[] pos = {12};
            for (int i = 0; i < qd; i++) {
                readName(d, pos);
                pos[0] += 4;
                if (pos[0] > len) return;
            }
            int total = an + ns + ar;
            for (int i = 0; i < total; i++) {
                if (pos[0] >= len) return;
                String name = readName(d, pos);
                if (pos[0] + 10 > len) return;
                int type = u16(d, pos[0]);
                int rdlen = u16(d, pos[0] + 8);
                pos[0] += 10;
                int rdata = pos[0];
                if (rdata + rdlen > len) return;

                if (type == 12) {                       // PTR -> service instance
                    int[] p = {rdata};
                    String inst = readName(d, p);
                    if (inst.contains(service) && !instances.contains(inst))
                        instances.add(inst);
                } else if (type == 33 && rdlen >= 6) {  // SRV -> port
                    srvPort.put(name, u16(d, rdata + 4));
                }
                pos[0] = rdata + rdlen;
            }
        } catch (Throwable ignored) { }
    }

    private static int u16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    private static String readName(byte[] d, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        int guard = 0;
        boolean jumped = false;
        while (p < d.length && guard++ < 128) {
            int len = d[p] & 0xFF;
            if (len == 0) { p++; break; }
            if ((len & 0xC0) == 0xC0) {
                if (p + 1 >= d.length) break;
                int ptr = ((len & 0x3F) << 8) | (d[p + 1] & 0xFF);
                if (!jumped) { pos[0] = p + 2; jumped = true; }
                p = ptr;
                continue;
            }
            p++;
            if (p + len > d.length) break;
            sb.append(new String(d, p, len, StandardCharsets.UTF_8)).append('.');
            p += len;
        }
        if (!jumped) pos[0] = p;
        String s = sb.toString();
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
