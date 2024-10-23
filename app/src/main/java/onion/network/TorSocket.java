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
import android.util.Log;

import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TorSocket extends Socket {

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

    public TorSocket(Context context, String onionAddress, int port) throws IOException {
        TorControlConnection torControlConnection = TorManager.getInstance(context).getTorControlConnection();
        if (torControlConnection != null) {
            Log.d("TorSocket", "Attempting to connect to " + onionAddress + ":" + port);
            String response = fetchOnionService(onionAddress, port);
            System.out.println(response);
        } else {
            throw new IOException("torControlConnection = null");
        }
    }

    private static String fetchOnionService(String onionAddress, int port) throws IOException {

        String TOR_HOST = "127.0.0.1";
        //String TOR_HOST = "localhost";
        int TOR_PORT = TorService.socksPort;

        // Налаштування проксі
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(TOR_HOST, TOR_PORT));

        // Налаштування OkHttpClient
        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS) // Тайм-аут на підключення
                .readTimeout(30, TimeUnit.SECONDS) // Тайм-аут на читання
                .build();

        // Створення запиту
        Request request = new Request.Builder()
                .url((port == 80 ? "http://" : "https://") + onionAddress)
                .build();

        if (!onionAddress.endsWith(".onion")) {
            throw new IllegalArgumentException("Invalid onion address: " + onionAddress);
        }

        // Виконання запиту
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e("TorSocket", "Request failed with code: " + response.code());
                throw new IOException("Unexpected code " + response);
            }
            return response.body().string();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
