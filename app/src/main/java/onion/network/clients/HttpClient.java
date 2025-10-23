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
        return request(uri, "GET", null, null, torified, allowTls, redirs);
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
    public static byte[] postbin(Uri uri, byte[] body, String contentType) throws IOException {
        return request(uri, "POST", body, contentType, true, false, 0);
    }

    public static String post(Uri uri, byte[] body, String contentType) throws IOException {
        return new String(postbin(uri, body, contentType), Utils.UTF_8);
    }

    private static byte[] request(Uri uri,
                                  String method,
                                  byte[] body,
                                  String contentType,
                                  boolean torified,
                                  boolean allowTls,
                                  int redirs) throws IOException {
        log(method + " " + uri + " tor=" + torified + " tls=" + allowTls + " redirs=" + redirs);
        if (redirs < 0) throw new IOException("Too many redirects");

        Context context = App.context;
        Socket socket = null;
        boolean hasBody = body != null && body.length > 0;
        if (contentType == null) contentType = "";
        byte[] responseContent = new byte[0];

        boolean tls = "https".equalsIgnoreCase(uri.getScheme()) && allowTls;

        try {
            int port = uri.getPort();
            if (port < 0) port = tls ? 443 : 80;

            if (torified) {
                while (!TorManager.getInstance(context).isReady()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                int torPort = TorManager.getInstance(context).getPort();
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", torPort));
                socket = new Socket(proxy);
                socket.connect(new InetSocketAddress(uri.getHost(), port), 60000);
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(uri.getHost(), port), 60000);
            }

            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = factory.createSocket(socket, uri.getHost(), port, true);
            }

            if (socket == null) throw new IOException("Socket init failed");

            OutputStream os = socket.getOutputStream();
            String path = uri.getEncodedPath();
            if (path == null || path.isEmpty()) path = "/";
            String query = uri.getEncodedQuery();
            if (query != null && !query.isEmpty()) {
                path += "?" + query;
            }

            writeLine(os, method + " " + path + " HTTP/1.1");
            writeHeader(os, "Host", uri.getHost());
            writeHeader(os, "Accept-Encoding", "gzip, deflate");
            writeHeader(os, "Connection", "close");
            if (hasBody) {
                writeHeader(os, "Content-Length", "" + body.length);
                if (!contentType.isEmpty()) {
                    writeHeader(os, "Content-Type", contentType);
                }
            }
            writeLine(os, "");
            if (hasBody) {
                os.write(body);
            }
            os.flush();

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
                if (line.startsWith("HTTP/")) continue;
                int idx = line.indexOf(":");
                if (idx > 0) headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            responseContent = baos.toByteArray();

            String encoding = headers.get("Content-Encoding");
            if ("gzip".equalsIgnoreCase(encoding)) {
                responseContent = decompressGzip(responseContent);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                responseContent = decompressDeflate(responseContent);
            }

            if ("GET".equalsIgnoreCase(method) && redirs > 0) {
                String loc = headers.get("Location");
                if (loc != null) {
                    log("redirect to " + loc);
                    responseContent = request(Uri.parse(loc), method, body, contentType, torified, allowTls, redirs - 1);
                }
            }

        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }

        return responseContent;
    }

    private static void writeLine(OutputStream os, String line) throws IOException {
        os.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void writeHeader(OutputStream os, String name, String value) throws IOException {
        writeLine(os, name + ": " + value);
    }

}
