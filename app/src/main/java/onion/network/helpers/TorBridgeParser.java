package onion.network.helpers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        List<String> resp = new ArrayList<>();
        // Отримуємо HTML з веб-сторінки бриджів
        String url = "https://bridges.torproject.org/bridges?transport=obfs4";
        if(ipv6) url = "https://bridges.torproject.org/bridges?transport=obfs4&ipv6=yes";
        String bridgeInfo = parseBridgelines(url);

        // Якщо це бридж obfs4, то витягти адресу і сертифікат
        if (bridgeInfo.startsWith("obfs4")) {
            String[] parts = bridgeInfo.split("obfs4");
            resp.add("obfs4" + parts[1]);
            resp.add("obfs4" + parts[2]);
        }
        return resp;
    }

    private static List<String> parseWebtunnelBridges() {
        List<String> resp = new ArrayList<>();
        // Отримуємо HTML з веб-сторінки бриджів
        String url = "https://bridges.torproject.org/bridges?transport=webtunnel&ipv6=yes";
        String bridgeInfo = parseBridgelines(url);

        // Якщо це бридж webtunnel, то витягти адресу і сертифікат
        if (bridgeInfo.startsWith("webtunnel")) {
            String[] parts = bridgeInfo.split("webtunnel");
            resp.add("webtunnel" + parts[1]);
            resp.add("webtunnel" + parts[2]);
        }
        return resp;
    }

    private static String parseBridgelines(String url) {
        try {
            Document doc = Jsoup.connect(url).get();

            // Знаходимо всі блоки з адресами бриджів (припускаючи, що це <pre> блоки)
            Element element = doc.getElementById("bridgelines");

            // Перебираємо всі знайдені бриджі
            return element.text();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }
}
