package onion.network.tor;

import java.net.*;
import java.util.*;
import java.util.regex.*;

public class BridgeChecker {

    // === TCP: перевіряє доступність основного хоста з bridge-рядка ===
    public static boolean isReachableTCP(String bridgeLine, int timeoutMs) {
        try {
            // Витягуємо щось типу "192.0.2.4:80" або "[2a01::123]:443"
            Matcher m = Pattern.compile("Bridge\\s+\\S+\\s+\\[?([0-9a-fA-F.:]+)]?:(\\d+)").matcher(bridgeLine);
            if (!m.find()) return false;
            String host = m.group(1);
            int port = Integer.parseInt(m.group(2));

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), timeoutMs);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // === UDP: шукає STUN-сервери в параметрі ice= і перевіряє хоч один ===
    public static boolean isLikelyUdpOpen(String bridgeLine, int timeoutMs) {
        try {
            Matcher iceMatcher = Pattern.compile("ice=([^\\s]+)").matcher(bridgeLine);
            if (!iceMatcher.find()) return false;

            // приклад: stun:stun.nextcloud.com:3478,stun:stun.voipgate.com:3478
            String iceList = iceMatcher.group(1);
            String[] servers = iceList.split(",");

            for (String stun : servers) {
                try {
                    // виділяємо хост і порт
                    Matcher m = Pattern.compile("stun:([^:]+):(\\d+)").matcher(stun);
                    if (!m.find()) continue;

                    String host = m.group(1);
                    int port = Integer.parseInt(m.group(2));

                    if (udpPing(host, port, timeoutMs)) {
                        return true; // достатньо одного STUN, який відповів
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return false;
    }

    // === Низькорівневий UDP пінг (короткий, без парсингу) ===
    private static boolean udpPing(String host, int port, int timeoutMs) {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            byte[] probe = new byte[] {0x00};
            DatagramPacket packet = new DatagramPacket(
                    probe, probe.length, InetAddress.getByName(host), port);
            ds.send(packet);

            byte[] buf = new byte[64];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}