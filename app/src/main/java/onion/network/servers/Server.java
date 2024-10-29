/*
 * Network.onion - fully distributed p2p social network using onion routing
 *
 * http://play.google.com/store/apps/details?id=onion.network
 * http://onionapps.github.io/Network.onion/
 * http://github.com/onionApps/Network.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network.servers;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;
import onion.network.FriendTool;
import onion.network.Item;
import onion.network.clients.HttpClient;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;
import onion.network.models.Request;
import onion.network.models.Response;
import onion.network.ui.views.RequestTool;
import onion.network.settings.Settings;
import onion.network.Site;
import onion.network.TorManager;

public class Server {

    private static Server instance;
    Context context;
    HttpServer httpServer;
    private String TAG = "Server";

    public Server(Context context) {
        this.context = context;
        log("start listening");
        startHttpServer(this::handleRequest);
    }

    synchronized public static Server getInstance(Context context) {
        if (instance == null) {
            instance = new Server(context.getApplicationContext());
        }
        return instance;
    }

    private void log(String s) {
        Log.i(TAG, s);
    }

    private Response handleRequest(NanoHTTPD. IHTTPSession session) {
        Request request =
                new Request(session.getMethod().name(), session.getUri(), session.getHeaders());
        Response response = new Response();
        Uri uri = Uri.parse(request.getPath());
        String path = uri.getPath();

        // Головна сторінка
        if (Settings.getPrefs(context).getBoolean("webprofile", true)) {
            if ("/".equals(path)) {
                String content = "<a href=\"network.onion\">/network.onion</a>";
                response.setStatus(303, "301 Moved Permanently");
                response.setContentHtml(content);
                response.putHeader("Location", "/network.onion");
                return response; // Повертаємо відповідь
            }

            if (!uri.getPathSegments().isEmpty() && "network.onion".equalsIgnoreCase(uri.getPathSegments().get(0))) {
                String host = request.getHeader("Host");
                if (host == null || !host.contains(TorManager.getInstance(context).getID())) {
                    host = TorManager.getInstance(context).getOnion();
                }
                uri = Uri.parse("http://" + host + uri.getPath());
                Site.Response rs = Site.getInstance(context).get(uri);
                response.setStatus(rs.getStatusCode(), rs.getStatusMessage());
                if (rs.getMimeType() != null && rs.getCharset() != null) {
                    response.setContent(rs.getData(), rs.getMimeType() + "; charset=" + rs.getCharset());
                } else {
                    response.setContent(rs.getData(), rs.getMimeType());
                }
                return response; // Повертаємо відповідь
            }
        }

        // Обробка запитів
        if (path.equals("/f")) {
            RequestTool.getInstance(context).handleRequest(uri);
            response.setStatus(200, "OK");
            response.setContentPlain("Friend request handled successfully.");
            return response; // Повертаємо відповідь
        }

        if (path.equals("/u")) {
            FriendTool.getInstance(context).handleUnfriend(uri);
            response.setStatus(200, "OK");
            response.setContentPlain("Unfriend request handled successfully.");
            return response; // Повертаємо відповідь
        }

        if (uri.getPath().equals("/a")) {
            String s;
            try {
                //String type = uri.getQueryParameter("t");
                String type = session.getParameters().get("t").get(0);
                //String index = uri.getQueryParameter("i");
                String index = session.getParameters().get("i").get(0);
                //int count = Integer.parseInt(uri.getQueryParameter("n"));
                int count = Integer.parseInt(
                        session.getParameters().get("n").get(0));
                ItemResult data = ItemDatabase.getInstance(context).get(type, index, count);

                // Обробка даних
                JSONArray items = new JSONArray();
                for (int i = 0; i < data.size(); i++) {
                    JSONObject o = new JSONObject();
                    o.put("t", data.at(i).type());
                    o.put("k", data.at(i).key());
                    o.put("i", data.at(i).index());
                    o.put("d", data.at(i).json(context, TorManager.getInstance(context).getID()));
                    items.put(o);
                }
                JSONObject o = new JSONObject();
                o.put("items", items);
                o.put("more", data.more());
                o.put("status", "ok");
                s = o.toString(4);
            } catch (Exception ex) {
                ex.printStackTrace();
                JSONObject o = new JSONObject();
                try {
                    o.put("status", "error");
                    s = o.toString(4);
                } catch (JSONException ex2) {
                    throw new RuntimeException(ex2);
                }
            }
            response.setContent(s.getBytes(Charset.forName("UTF-8")), "application/json; charset=utf-8");
            return response; // Повертаємо відповідь
        }

        // Інші обробки запитів
        if (uri.getPath().equals("/i")) {
            //String type = uri.getQueryParameter("t");
            String type = session.getParameters().get("t").get(0);
            ItemResult data = ItemDatabase.getInstance(context).get(type, "", 1);
            response.setContent(data.one().data());
            return response; // Повертаємо відповідь
        }

        if (uri.getPath().equals("/m")) {
            response.setContentPlain(ChatServer.getInstance(context).handle(session) ? "1" : "0");
            return response; // Повертаємо відповідь
        }

        if (uri.getPath().equals("/r")) {
            response.setContentPlain(FriendTool.getInstance(context).handleUpdate(uri) ? "1" : "0");
            return response; // Повертаємо відповідь
        }

        // Якщо шлях не знайдений
        response.setStatus(404, "Not Found");
        response.setContentPlain("404 Not Found");
        return response; // Повертаємо відповідь
    }

    private void startHttpServer(HttpServer.HttpServerListener httpServerListener) {
        httpServer = new HttpServer(8080, httpServerListener); // Використовуємо порт 8080
        try {
            httpServer.start();
            log("HTTP Server started on port 8080");
        } catch (IOException e) {
            log("Failed to start HTTP server: " + e.getMessage());
        }
    }

    public void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    private static class HttpServer extends NanoHTTPD {

        private HttpServerListener listener;

        public HttpServer(int port, HttpServerListener listener) {
            super(port);
            this.listener = listener;
        }

        interface HttpServerListener {
            onion.network.models.Response serve(NanoHTTPD.IHTTPSession session);
        }

        @Override
        public Response serve(NanoHTTPD.IHTTPSession session) {
            onion.network.models.Response listenerResponse = listener.serve(session);

            // Формуйте нову відповідь, перевіряючи статус код
            switch (listenerResponse.getStatusCode()) {
                case 200:
                    return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            listenerResponse.getContentType(),
                            new String(listenerResponse.getContent(), StandardCharsets.UTF_8) // Перетворення byte[] на String
                    );
                case 404:
                    return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.NOT_FOUND,
                            listenerResponse.getContentType(),
                            new String(listenerResponse.getContent(), StandardCharsets.UTF_8)
                    );
                case 500:
                    return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            listenerResponse.getContentType(),
                            new String(listenerResponse.getContent(), StandardCharsets.UTF_8)
                    );
                default:
                    return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "Unexpected error occurred."
                    );
            }
        }
    }
}
