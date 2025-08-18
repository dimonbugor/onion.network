package onion.network.clients;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import onion.network.App;
import onion.network.helpers.Utils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

        Context context = App.context;
        if (redirs < 0) throw new IOException("Too many redirects");

        HashMap<String, String> headers = new HashMap<>();
        byte[] content = new byte[0];
        Socket socket = null;

        boolean tls = "https".equalsIgnoreCase(uri.getScheme()) && allowTls;

        try {
            // Визначаємо порт
            int port = uri.getPort();
            if (port < 0) port = tls ? 443 : 80;

            // Відкриваємо сокет
            if (torified) {
                socket = new TorSocket(context, uri.getHost(), port);
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(uri.getHost(), port), 10000);
            }

            // TLS якщо потрібно
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = factory.createSocket(socket, uri.getHost(), port, true);
            }

            // Відправляємо HTTP GET
            OutputStream os = socket.getOutputStream();
            String req = uri.getEncodedPath();
            if (uri.getEncodedQuery() != null) req += "?" + uri.getEncodedQuery();
            writeLine(os, "GET " + req + " HTTP/1.0");
            writeHeader(os, "Host", uri.getHost());
            writeHeader(os, "Accept-Encoding", "gzip, deflate");
            writeLine(os, "");
            os.flush();

            // Читаємо заголовки
            InputStream is = socket.getInputStream();
            for (int il = 0; ; il++) {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    int c = is.read();
                    if (c < 0) throw new IOException();
                    if (c == '\n') break;
                    sb.append((char) c);
                }
                String l = sb.toString().trim();
                if (l.equals("")) break;
                if (il == 0 && !l.startsWith("HTTP/")) throw new IOException();

                String[] hh = l.split("\\:", 2);
                if (hh.length == 2) headers.put(hh[0].trim(), hh[1].trim());
            }

            // Лог заголовків
            for (Map.Entry<String, String> p : headers.entrySet()) log(p.getKey() + ": " + p.getValue());

            // Визначаємо довжину контенту
            int len = 1024 * 512; // max
            String slen = headers.get("Content-Length");
            if (slen != null) {
                try { len = Integer.parseInt(slen); } catch (NumberFormatException ex) { throw new IOException(ex); }
            }
            if (len > 1024 * 512) throw new IOException("Content too large");

            // Читаємо тіло
            ByteArrayOutputStream ws = new ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            for (int i = 0; i < len; ) {
                int n = is.read(buf);
                if (n < 0) break;
                ws.write(buf, 0, n);
                i += n;
            }
            ws.close();
            content = ws.toByteArray();

            // Обробка gzip/deflate
            InputStream zis = null;
            String encoding = headers.get("Content-Encoding");
            if ("gzip".equalsIgnoreCase(encoding)) zis = new GZIPInputStream(new ByteArrayInputStream(content));
            if ("deflate".equalsIgnoreCase(encoding)) zis = new InflaterInputStream(new ByteArrayInputStream(content));

            if (zis != null) {
                ws = new ByteArrayOutputStream();
                for (;;) {
                    int n = zis.read(buf);
                    if (n < 0) break;
                    ws.write(buf, 0, n);
                }
                ws.close();
                content = ws.toByteArray();
            }

        } finally {
            if (socket != null) socket.close();
        }

        // Редіректи
        if (redirs > 0) {
            String loc = headers.get("Location");
            if (loc != null) {
                log("redirect to " + loc);
                content = getbin(Uri.parse(loc), torified, allowTls, redirs - 1);
            }
        }

        return content;
    }

    // допоміжні методи
    private static void writeLine(OutputStream os, String line) throws IOException {
        os.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void writeHeader(OutputStream os, String name, String value) throws IOException {
        writeLine(os, name + ": " + value);
    }

}