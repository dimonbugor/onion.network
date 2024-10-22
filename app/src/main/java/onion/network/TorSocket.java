/*
 * Network.onion - fully distributed p2p social network using onion routing
 *
 * http://play.google.com/store/apps/details?id=onion.network
 * http://onionapps.github.io/Network.onion/
 * http://github.com/onionApps/Network.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network;

import android.content.Context;

import net.freehaven.tor.control.TorControlConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TorSocket extends Socket {

    static final int timeout = 60000;

    /*public TorSocket(Context context, String host, int port) throws IOException {

        // Отримуємо TorManager і порт
        TorManager torManager = TorManager.getInstance(context);
        int torPort = torManager.getPort(); // Порт Tor
        // Створюємо з'єднання з Tor через TorControlConnection
        TorControlConnection torControlConnection = torManager.getTorControlConnection();

        // Переконайтеся, що з'єднання існує
        if (torControlConnection != null) {
            // Тут ви можете реалізувати логіку для отримання даних з сервісу
            try (Socket socket = new Socket("127.0.0.1", torPort)) {
                // Підключення через SOCKS-проксі
                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();

                // Відправка SOCKS запиту
                os.write(4); // SOCKS 4A
                os.write(1); // Stream
                os.write((port >> 8) & 0xff); // Високий байт порту
                os.write((port >> 0) & 0xff); // Низький байт порту

                // IP адреса, до якої ви підключаєтеся
                os.write(0); // IP 0.0.0.0
                os.write(0);
                os.write(0);
                os.write(1); // 127.0.0.1

                os.write(0); // Вказуємо кінець IP

                // Ім'я хоста
                os.write(host.getBytes());
                os.write(0); // Кінець рядка

                os.flush();

                // Отримуємо відповідь від проксі
                byte[] response = new byte[8];
                is.read(response);

                // Перевіряємо, чи з'єднання успішне
                if (response[1] != 90) { // 90 - з'єднання успішно
                    throw new IOException("Connection failed: " + response[1]);
                }

                try {
                    os.close();
                    is.close();
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }*/

    public TorSocket(Context context, String onionUrl, int port) throws IOException {
        String scheme = "http://";
        if (port != 80) {
            scheme = "https://";
        }
        if (onionUrl.contains("http")) {
            return;
        } else {
            onionUrl = scheme + onionUrl;
        }
        // Отримуємо TorManager і порт
        TorManager torManager = TorManager.getInstance(context);
        int torPort = torManager.getPort(); // Порт Tor
        // Створюємо з'єднання з Tor через TorControlConnection
        TorControlConnection torControlConnection = torManager.getTorControlConnection();
        // Переконайтеся, що з'єднання існує
        if (torControlConnection != null) {

            // Налаштування OkHttpClient з SOCKS проксі
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", torPort))) // Порт Tor
                    .build();

            Request request = new Request.Builder()
                    .url(onionUrl)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    System.out.println("Response: " + response.body().string());
                } else {
                    System.out.println("Request failed: " + response.code());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            throw new IOException("TorControlConnection is null. Check Tor Manager.");
        }
    }
}
