package onion.network;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.torproject.jni.TorService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpClient {

    private static void log(String str) {
        Log.i("HTTP", str);
    }

    public static String getNoTor(Uri uri) throws IOException {
        return new String(getbin(uri, false, true, 2), Utils.utf8);
    }

    public static String get(Uri uri) throws IOException {
        return new String(getbin(uri), Utils.utf8);
    }

    public static String get(String uriStr) throws IOException {
        return get(Uri.parse(uriStr));
    }

    public static byte[] getbin(Uri uri) throws IOException {
        return getbin(uri, true, false, 0);
    }

    private static byte[] getbin(Uri uri, boolean torified, boolean allowTls, int redirs) throws IOException {
        uri = Uri.parse("http://check.torproject.org");
        log("request " + uri + " " + torified + " " + allowTls + " " + redirs);

        byte[] content;
        HashMap<String, String> headers = new HashMap<>();

        // Налаштування OkHttpClient з тайм-аутами
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // Тайм-аут на підключення
                .readTimeout(60, TimeUnit.SECONDS); // Тайм-аут на читання

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
                .url(uri.toString())
                .addHeader("Accept-Encoding", "gzip, deflate");

        // Додати заголовки TLS, якщо потрібні
        if (allowTls && "https".equals(uri.getScheme())) {
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
                content = getbin(Uri.parse(headerLocation), torified, allowTls, redirs - 1);
            }
        }

        return content;
    }
}