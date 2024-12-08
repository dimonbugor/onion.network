package onion.network.helpers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TorBridgeParser {

    public static List<String> parseBridges() {
        List<String> resp = new ArrayList<>();
        try {
            // Отримуємо HTML з веб-сторінки бриджів
            String url = "https://bridges.torproject.org/bridges?transport=obfs4";
            Document doc = Jsoup.connect(url).get();

            // Знаходимо всі блоки з адресами бриджів (припускаючи, що це <pre> блоки)
            Element element = doc.getElementById("bridgelines");

            // Перебираємо всі знайдені бриджі
            String bridgeInfo = element.text();

            // Якщо це бридж obfs4, то витягти адресу і сертифікат
            if (bridgeInfo.startsWith("obfs4")) {
                String[] parts = bridgeInfo.split("obfs4");
                resp.add("obfs4" + parts[1]);
                resp.add("obfs4" + parts[2]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return resp;
    }
}
