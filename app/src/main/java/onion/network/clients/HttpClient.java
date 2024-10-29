package onion.network.clients;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.torproject.jni.TorService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import onion.network.App;
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
        log("request " + uriString + " " + torified + " " + allowTls + " " + redirs);

        if (redirs < 0) {
            throw new IOException("Too many redirects");
        }

        byte[] content;
        HashMap<String, String> headers = new HashMap<>();
        OkHttpClient client = buildClient(torified); // Метод для побудови клієнта з або без проксі

        Request.Builder requestBuilder = new Request.Builder().url(uriString);
        if (allowTls && uriString.startsWith("https")) {
            requestBuilder.addHeader("Host", Uri.parse(uriString).getHost());
        }

        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }

            if (response.body() != null) {
                content = response.body().bytes();
            } else {
                throw new IOException("Empty response body");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }

        // Redirect handling
        String location = headers.get("Location");
        if (location != null) {
            return getbin(location, torified, allowTls, redirs - 1);
        }

        return content;
    }

    private static OkHttpClient buildClient(boolean torified) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(240, TimeUnit.SECONDS)
                .readTimeout(240, TimeUnit.SECONDS);
        if (torified) {
            builder.proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TorService.socksPort)));
        }
        return builder.build();
    }
}