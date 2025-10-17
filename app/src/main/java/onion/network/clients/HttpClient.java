package onion.network.clients;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import javax.net.ssl.SSLSocketFactory;

import onion.network.App;
import onion.network.TorManager;
import onion.network.helpers.Utils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class HttpClient {

    private static void log(String str) {
        Log.i("HTTP", str);
    }

    public static String get(Uri uri) throws IOException {
        return new String(getbin(uri, true, false, 0), Utils.UTF_8);
    }

    public static String getNoTor(Uri uri) throws IOException {
        return new String(getbin(uri, false, true, 2), Utils.UTF_8);
    }

    public static byte[] getbin(Uri uri) throws IOException {
        return getbin(uri, true, false, 0);
    }

    private static byte[] getbin(Uri uri, boolean torified, boolean allowTls, int redirs) throws IOException {
        log("request " + uri + " " + torified + " " + allowTls + " " + redirs);

        if (redirs < 0) throw new IOException("Too many redirects");

        Context context = App.context;
        byte[] content = new byte[0];
        Socket socket = null;
        boolean tls = "https".equalsIgnoreCase(uri.getScheme()) && allowTls;

        try {
            int port = uri.getPort();
            if (port < 0) port = tls ? 443 : 80;

            // Відкриваємо сокет
            if (torified) {
                while (!TorManager.getInstance(context).isReady()) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                int torPort = TorManager.getInstance(context).getPort();
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", torPort));
                socket = new Socket(proxy);
                socket.connect(new InetSocketAddress(uri.getHost(), port), 60000);
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(uri.getHost(), port), 60000);
            }

            // TLS якщо потрібно
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = factory.createSocket(socket, uri.getHost(), port, true);
            }

            if (socket == null) throw new IOException("Socket init failed");

            // Відправляємо HTTP GET
            OutputStream os = socket.getOutputStream();
            String path = uri.getEncodedPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getEncodedQuery() != null) path += "?" + uri.getEncodedQuery();

            writeLine(os, "GET " + path + " HTTP/1.1");
            writeHeader(os, "Host", uri.getHost());
            writeHeader(os, "Accept-Encoding", "gzip, deflate");
            writeHeader(os, "Connection", "close"); // важливо для HTTP/1.1
            writeLine(os, "");
            os.flush();

            // Читаємо заголовки
            InputStream is = socket.getInputStream();
            Map<String, String> headers = new HashMap<>();
            while (true) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\n') break;
                    if (c != '\r') sb.append((char) c);
                }
                String line = sb.toString().trim();
                if (line.isEmpty()) break;

                if (line.startsWith("HTTP/")) continue; // ігноруємо статусний рядок
                int idx = line.indexOf(":");
                if (idx > 0) headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }

            // Читаємо body до кінця потоку
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            content = baos.toByteArray();

            // Обробка gzip/deflate
            String encoding = headers.get("Content-Encoding");
            if ("gzip".equalsIgnoreCase(encoding)) {
                content = decompressGzip(content);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                content = decompressDeflate(content);
            }

            // Редіректи
            if (redirs > 0) {
                String loc = headers.get("Location");
                if (loc != null) {
                    log("redirect to " + loc);
                    content = getbin(Uri.parse(loc), torified, allowTls, redirs - 1);
                }
            }

        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }

        return content;
    }

    private static byte[] decompressGzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private static byte[] decompressDeflate(byte[] data) throws IOException {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = iis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    // допоміжні методи
    private static void writeLine(OutputStream os, String line) throws IOException {
        os.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void writeHeader(OutputStream os, String name, String value) throws IOException {
        writeLine(os, name + ": " + value);
    }

}