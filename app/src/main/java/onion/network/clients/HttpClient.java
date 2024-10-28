package onion.network.clients;

import android.net.Uri;
import android.util.Log;

import org.torproject.jni.TorService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import onion.network.helpers.Utils;

public class HttpClient {

    private static void log(String str) {
        Log.i("HTTP", str);
    }

    public static String getNoTor(String uriString) throws IOException {
        return new String(getbin(uriString, false, true, 2), Utils.UTF_8);
    }

    public static String get(String uriString) throws IOException {
        return new String(getbin(uriString), Utils.UTF_8);
    }

    public static byte[] getbin(String uriString) throws IOException {
        return getbin(uriString, true, false, 0);
    }

    public static byte[] getbin(String uriString, boolean torified, boolean allowTls, int redirs) throws IOException {
        // Перевіряємо, чи містить URI схему
        if (uriString.isEmpty() || uriString.equals("/")) {
            throw new IllegalArgumentException("Invalid URI: " + uriString);
        }

        if (uriString.startsWith("/")) {
            uriString = uriString.substring(1); // Видаляємо перший символ, якщо є
        }
        uriString = "http://" + uriString;

        log("request " + uriString + " " + torified + " " + allowTls + " " + redirs);

        byte[] content;
        HashMap<String, String> headers = new HashMap<>();

        // Налаштування OkHttpClient з тайм-аутами
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(240, TimeUnit.SECONDS) // Збільшено до 120 секунд
                .readTimeout(240, TimeUnit.SECONDS);

        // Додавання проксі для Tor, якщо потрібно
        if (torified) {
            String TOR_HOST = "127.0.0.1";
            int TOR_PORT = TorService.socksPort; // Змінити на правильний порт
            clientBuilder.proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(TOR_HOST, TOR_PORT)));
        }

        // Створення OkHttpClient
        OkHttpClient client = clientBuilder.build();

        // Створення запиту
        Request.Builder requestBuilder = new Request.Builder()
                .url(uriString)
                .addHeader("Accept-Encoding", "gzip, deflate");

        // Додати заголовки TLS, якщо потрібні
        if (allowTls && uriString.contains("https")) {
            Uri uri = Uri.parse(uriString);
            requestBuilder.addHeader("Host", uri.getHost());
        }

        Request request = requestBuilder.build();

        // Виконання запиту
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // Зберігання заголовків
            for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                headers.put(entry.getKey(), entry.getValue().get(0)); // Беремо тільки перший заголовок
            }

            if (response.body() != null) {
                content = response.body().bytes();
            } else {
                log("response.body() == null");
                throw new IOException("response.body() == null"); // Перекидаємо виключення далі
            }
        } catch (IOException e) {
            log("Error fetching URL: " + e.getMessage());
            throw e; // Перекидаємо виключення далі
        }

        // Обробка редиректів
        if (redirs > 0) {
            String headerLocation = headers.get("Location");
            if (headerLocation != null) {
                log("redirection to " + headerLocation);
                content = getbin(headerLocation, torified, allowTls, redirs - 1);
            }
        }

        return content;
    }
}