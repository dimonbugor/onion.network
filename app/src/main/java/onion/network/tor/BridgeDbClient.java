package onion.network.tor;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal HTTP client for interacting with BridgeDB.
 */
class BridgeDbClient {

    private static final String TAG = "BridgeDbClient";
    private static final String BASE_URL = "https://bridges.torproject.org/bridges";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36";

    private final OkHttpClient client;
    private final BridgeResponseParser parser = new BridgeResponseParser();

    BridgeDbClient() {
        client = new OkHttpClient.Builder()
                .callTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    BridgeFetchResult fetch(String transport, boolean ipv6) throws IOException {
        return fetch(transport, ipv6, null, null);
    }

    BridgeFetchResult fetch(String transport, boolean ipv6, String captchaText, String captchaSecret) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("transport", transport);
        if (ipv6) {
            urlBuilder.addQueryParameter("ipv6", "yes");
        }

        Request request;
        if (captchaText != null && !captchaText.isEmpty()
                && captchaSecret != null && !captchaSecret.isEmpty()) {
            FormBody body = new FormBody.Builder()
                    .add("captcha_challenge_field", captchaSecret)
                    .add("captcha_response_field", captchaText)
                    .add("submit", "submit")
                    .build();
            request = new Request.Builder()
                    .url(urlBuilder.build())
                    .post(body)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Connection", "close")
                    .build();
        } else {
            request = new Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Connection", "close")
                    .build();
        }

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("BridgeDB response code " + response.code());
            }
            InputStream bodyStream = response.body() != null ? response.body().byteStream() : null;
            return parser.parse(bodyStream);
        } catch (IOException e) {
            Log.w(TAG, "BridgeDB request failed for transport " + transport, e);
            throw e;
        }
    }
}
