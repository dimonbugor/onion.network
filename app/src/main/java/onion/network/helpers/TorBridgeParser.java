package onion.network.helpers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TorBridgeParser {

    public static List<String> getBridgeConfigs() {
        List<String> bridgeConfigs = new ArrayList<>();
        List<String> bridgeLines = parseBridges();

        if (!bridgeLines.isEmpty()) {
            for (String bridge : bridgeLines) {
                if (bridge == null) continue;
                String val = bridge.trim();
                if (val.isEmpty()) continue;
                // Якщо парсер вже повернув 'Bridge ...' — прибираємо префікс
                if (val.toLowerCase().startsWith("bridge ")) {
                    val = val.substring(6).trim();
                }
                bridgeConfigs.add("Bridge " + val);
            }
        }

        // Додаємо meek_lite і snowflake
//        bridgeConfigs.add("Bridge meek 0.0.2.0:3 url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com");
//        bridgeConfigs.add("Bridge meek_lite 192.0.2.20:80 url=https://1314488750.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN");
//        bridgeConfigs.add("Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn");
//        bridgeConfigs.add("Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn");
        return bridgeConfigs;
    }

    private static List<String> parseBridges() {
        List<String> resp = new ArrayList<>();
        resp.addAll(parseObfsBridges(false));
        resp.addAll(parseObfsBridges(true));
        resp.addAll(parseWebtunnelBridges());
        return resp;
    }

    private static List<String> parseObfsBridges(boolean ipv6) {
        String url = "https://bridges.torproject.org/bridges?transport=obfs4";
        if (ipv6) url = "https://bridges.torproject.org/bridges?transport=obfs4&ipv6=yes";
        return parseBridgelines(url);
    }

    private static List<String> parseWebtunnelBridges() {
        String url = "https://bridges.torproject.org/bridges?transport=webtunnel&ipv6=yes";
        return parseBridgelines(url);
    }

    private static List<String> parseBridgelines(String url) {
        List<String> bridgeLines = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(url).get();
            Element element = doc.getElementById("bridgelines");
            if (element == null) return bridgeLines;

            String input = element.text();

            Pattern bridgePattern = Pattern.compile(
                    "(\\S+)\\s+(\\S+)\\s+([0-9A-F]{40})((?:\\s+\\S+=\\S+)*)"
            );

            Matcher m = bridgePattern.matcher(input);
            while (m.find()) {
                String transport = m.group(1);
                String hostPort  = m.group(2);
                String fp        = m.group(3);
                String params    = m.group(4).trim();

                String host;
                String port;

                if (hostPort.startsWith("[")) {
                    int idx = hostPort.indexOf("]:");
                    host = hostPort.substring(1, idx);
                    port = hostPort.substring(idx + 2);
                } else if (hostPort.chars().filter(ch -> ch == ':').count() > 1) {
                    // IPv6 без дужок → розділяємо за останньою двокрапкою
                    int idx = hostPort.lastIndexOf(':');
                    host = hostPort.substring(0, idx);
                    port = hostPort.substring(idx + 1);
                } else {
                    // IPv4
                    int idx = hostPort.indexOf(':');
                    host = hostPort.substring(0, idx);
                    port = hostPort.substring(idx + 1);
                }

                // після визначення host/port
                if (host.contains(":") && !host.startsWith("[")) {
                    host = "[" + host + "]";
                }

                bridgeLines.add(buildBridgeLine(transport, host, port, fp, params));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bridgeLines;
    }

    public static String buildBridgeLine(String transport, String host, String port,
                                         String fingerprint, String params) {
        StringBuilder sb = new StringBuilder();
        sb.append(transport).append(' ')
                .append(host).append(':').append(port).append(' ')
                .append(fingerprint);
        if (!params.isEmpty()) {
            sb.append(' ').append(params);
        }
        return sb.toString();
    }


}
