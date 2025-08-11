package onion.network.clients;

import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
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

    public synchronized static byte[] getbin(String uriString, boolean torified, boolean allowTls, int redirs) throws IOException {
        log("request " + uriString + " " + torified + " " + allowTls + " " + redirs);

        if (redirs < 0) {
            throw new IOException("Too many redirects");
        }

        byte[] content;
        HashMap<String, String> headers = new HashMap<>();
        OkHttpClient client = buildClient(torified); // Метод для побудови клієнта з або без проксі

        Request.Builder requestBuilder = new Request.Builder().url(uriString);

        // Перевірка на HTTPS та додавання заголовка Host
        if (allowTls && uriString.startsWith("https")) {
            requestBuilder.addHeader("Host", Uri.parse(uriString).getHost());
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            // Перевірка на успішний статус коду
            if (!response.isSuccessful()) {
                // Виводимо статус-код і повідомлення для налагодження
                log("Unexpected response: " + response.code() + " " + response.message());
                throw new IOException("Unexpected code " + response);
            }

            // Збір заголовків
            for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }

            // Отримання вмісту
            if (response.body() != null) {
                content = response.body().bytes();
            } else {
                throw new IOException("Empty response body");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }

        // Обробка перенаправлень
        String location = headers.get("Location");
        if (location != null) {
            return getbin(location, torified, allowTls, redirs - 1);
        }

        return content;
    }

//    private static OkHttpClient buildClient(boolean torified) throws IOException {
//        OkHttpClient.Builder builder = new OkHttpClient.Builder()
//                .connectTimeout(240, TimeUnit.SECONDS)
//                .readTimeout(240, TimeUnit.SECONDS);
//
//        if (torified) {
//            // Отримуємо параметри моста
//            List<String> bridgeLines = TorBridgeParser.parseBridges();
//            Properties options = new Properties(); // Режим обфускації
//            for (String bridgeLine: bridgeLines) {
//                setPropertiesFromBridgeString(options, bridgeLine);
//                break;
//            }
//
//            // Створюємо з'єднання Obfs4
//            String remoteAddress = options.getProperty(Obfs4Transport.OPTION_ADDRESS);
//            String remotePort = options.getProperty(Obfs4Transport.OPTION_PORT);
//            Obfs4Transport.Obfs4Connection connection = new Obfs4Transport.Obfs4Connection(
//                    remoteAddress, remotePort, InetAddress.getLocalHost(), 9050); // 9050 — стандартний порт SOCKS5 для Tor
//
//            // Створюємо сокет через Obfs4Connection
//            Socket socket = connection.getSocket(remoteAddress, 443);
//
//            // Налаштовуємо проксі для OkHttp
//            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socket.getLocalAddress(), 9050));
//            builder.proxy(proxy);
//        }
//
//        return builder.build();
//    }

    private static OkHttpClient buildClient(boolean torified) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(240, TimeUnit.SECONDS)
                .readTimeout(240, TimeUnit.SECONDS);

        if (torified) {
            // Проксі на Tor (SOCKS5)
            Proxy proxy = new Proxy(
                    Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", 9050)
            );
            builder.proxy(proxy);
        }

        return builder.build();
    }

}