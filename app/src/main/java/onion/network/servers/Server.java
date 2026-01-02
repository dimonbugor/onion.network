package onion.network.servers;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.util.Log;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import onion.network.helpers.StreamMediaStore;

import onion.network.models.FriendTool;
import onion.network.models.Item;
import onion.network.models.Site;
import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.models.ItemResult;
import onion.network.settings.Settings;
import onion.network.helpers.Utils;
import onion.network.ui.views.RequestTool;

public class Server {

    private static Server instance;
    Context context;
    String socketName;
    HttpServer httpServer;
    LocalSocket ls;
    LocalServerSocket lss;
    private String TAG = "Server";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public Server(Context context) {
        this.context = context;
        log("start listening");
        try {
            socketName = new File(context.getFilesDir(), "socket").getAbsolutePath();
            ls = new LocalSocket();
            ls.bind(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.FILESYSTEM));
            lss = new LocalServerSocket(ls.getFileDescriptor());
            socketName = "unix:" + socketName;
            log(socketName);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        httpServer = new HttpServer(lss, new HttpServer.Handler() {
            @Override
            public void handle(HttpServer.Request request, HttpServer.Response response) {
                handleRequest(request, response);
            }
        });
        httpServer.start();
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

    private void handleRequest(HttpServer.Request request, HttpServer.Response response) {

        Uri uri = Uri.parse(request.getPath());
        String path = uri.getPath();

        if (!authOk(uri)) {
            response.setStatus(403, "Forbidden");
            response.setContentPlain("Forbidden");
            return;
        }

        if ("/upload".equals(path)) {
            handleUpload(request, response);
            return;
        }

        if (path != null && path.startsWith("/media/")) {
            handleMediaDownload(request, response, path.substring("/media/".length()));
            return;
        }

        if ("/wallbot.xml".equals(path) || "/rss".equals(path)) {
            int n = 50;
            try {
                n = Integer.parseInt(uri.getQueryParameter("n"));
            } catch (Exception ignored) {
            }
            String xml = buildWallBotXml(context, n);
            response.setStatus(200, "OK");
            response.setContent(xml.getBytes(UTF_8), "text/xml; charset=utf-8");
            return;
        }

        if (Settings.getPrefs(context).getBoolean("webprofile", true)) {
            if ("/".equals(path)) {
                String content = "<a href=\"network.onion\">/network.onion</a>";
                response.setStatus(303, "301 Moved Permanently");
                response.setContentHtml(content);
                response.putHeader("Location", "/network.onion");
                return;

            }
            if (((!uri.getPathSegments().isEmpty() && "network.onion".equalsIgnoreCase(uri.getPathSegments().get(0))))) {
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
                return;
            }
        }

        if (path.equals("/f")) {
            RequestTool.getInstance(context).handleRequest(uri);
            return;
        }

        if (path.equals("/u")) {
            FriendTool.getInstance(context).handleUnfriend(uri);
            return;
        }

        if (uri.getPath().equals("/a")) {
            String s = "";
            try {
                String type = uri.getQueryParameter("t");
                String index = uri.getQueryParameter("i");
                int count = Integer.parseInt(uri.getQueryParameter("n"));
                String knownHashParam = uri.getQueryParameter("h");
                boolean skipContent = "1".equals(uri.getQueryParameter("skip"));
                ItemResult data = ItemDatabase.getInstance(context).get(type, index, count);
                if (data.size() == 0 && "name".equals(type) && "".equals(index) && count == 1) {
                    List<Item> il = new ArrayList<>();
                    il.add(new Item("name", "", "", new JSONObject()));
                    data = new ItemResult(il, null, true, false);
                }
                JSONArray items = new JSONArray();
                TorManager torManager = TorManager.getInstance(context);
                String requestAddress = torManager.getID();
                boolean canUseHashSkip = skipContent && knownHashParam != null && !knownHashParam.isEmpty() && data.size() == 1;
                for (int i = 0; i < data.size(); i++) {
                    JSONObject o = new JSONObject();
                    o.put("t", data.at(i).type());
                    o.put("k", data.at(i).key());
                    o.put("i", data.at(i).index());
                    JSONObject serialized = data.at(i).json(context, requestAddress);
                    String payloadHash = Utils.sha256Base64(data.at(i).data());
                    o.put("h", payloadHash);
                    if (canUseHashSkip && payloadHash.equals(knownHashParam)) {
                        o.put("unchanged", true);
                    } else {
                        o.put("d", serialized);
                    }
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
            return;
        }

        if (uri.getPath().equals("/i")) {
            String type = uri.getQueryParameter("t");
            ItemResult data = ItemDatabase.getInstance(context).get(type, "", 1);
            response.setContent(data.one().data());
            return;
        }

        if (uri.getPath().equals("/m")) {
            response.setContentPlain(ChatServer.getInstance(context).handle(request) ? "1" : "0");
            return;
        }

        if(uri.getPath().equals("/r")) {
            response.setContentPlain(FriendTool.getInstance(context).handleUpdate(uri) ? "1" : "0");
            return;
        }

    }

    private void handleUpload(HttpServer.Request request, HttpServer.Response response) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(405, "Method Not Allowed");
            response.setContentPlain("Method Not Allowed");
            return;
        }
        if (!request.hasBody()) {
            response.setStatus(400, "Bad Request");
            response.setContentPlain("Empty body");
            return;
        }
        String mime = request.getHeader("Content-Type", "application/octet-stream");
        byte[] body = request.getBody();
        if (body == null || body.length == 0) {
            response.setStatus(400, "Bad Request");
            response.setContentPlain("Empty body");
            return;
        }
        try {
            StreamMediaStore.MediaDescriptor descriptor = StreamMediaStore.save(context, body, mime);
            JSONObject payload = new JSONObject();
            payload.put("status", "ok");
            payload.put("id", descriptor.id);
            payload.put("mime", descriptor.mime);
            payload.put("size", descriptor.size);
            writeJson(response, payload, 201, "Created");
        } catch (IOException ex) {
            boolean tooLarge = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("exceed");
            JSONObject payload = new JSONObject();
            try {
                payload.put("status", "error");
                payload.put("message", tooLarge ? "Media exceeds limit" : "Unable to store media");
            } catch (JSONException ignore) {
            }
            writeJson(response, payload, tooLarge ? 413 : 500, tooLarge ? "Payload Too Large" : "Internal Server Error");
        } catch (JSONException ex) {
            response.setStatus(500, "Internal Server Error");
            response.setContentPlain("JSON error");
        }
    }

    private void handleMediaDownload(HttpServer.Request request, HttpServer.Response response, String mediaId) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(405, "Method Not Allowed");
            response.setContentPlain("Method Not Allowed");
            return;
        }
        if (TextUtils.isEmpty(mediaId)) {
            response.setStatus(400, "Bad Request");
            response.setContentPlain("Missing media id");
            return;
        }
        int slash = mediaId.indexOf('/') ;
        if (slash >= 0) {
            mediaId = mediaId.substring(0, slash);
        }
        StreamMediaStore.MediaDescriptor descriptor = StreamMediaStore.get(context, mediaId);
        if (descriptor == null || descriptor.file == null || !descriptor.file.exists()) {
            response.setStatus(404, "Not Found");
            response.setContentPlain("Not Found");
            return;
        }
        byte[] data = Utils.readFileAsBytes(descriptor.file);
        response.putHeader("Cache-Control", "no-store");
        response.setContent(data, descriptor.mime);
    }

    private void writeJson(HttpServer.Response response, JSONObject payload, int statusCode, String statusText) {
        if (payload == null) {
            payload = new JSONObject();
        }
        byte[] bytes = payload.toString().getBytes(UTF_8);
        response.setStatus(statusCode, statusText);
        response.setContent(bytes, "application/json; charset=utf-8");
    }

    public String getSocketName() {
        return socketName;
    }

    public LocalSocket getLs() {
        return ls;
    }

    private boolean authOk(Uri uri) {
        String token = Settings.getPrefs(context).getString("authtoken", "");
        if (token == null || token.trim().isEmpty()) {
            return true;
        }
        String q = uri.getQueryParameter("auth");
        return token.equals(q);
    }

    private String buildWallBotXml(Context context, int limit) {
        ItemResult result = ItemDatabase.getInstance(context).get("post", "", limit);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<rss version=\"2.0\"><channel>");
        sb.append("<title>").append(escapeXml("Profile Feed")).append("</title>");

        for (int i = 0; i < result.size(); i++) {
            Item it = result.at(i);
            JSONObject o = it.json();
            String guid = o.optString("event_id", it.key());
            String title = o.optString("title", o.optString("text", "post"));
            String link = o.optString("link", "");
            String description = o.toString();

            sb.append("<item>");
            sb.append("<guid>").append(escapeXml(guid)).append("</guid>");
            sb.append("<title>").append(escapeXml(title)).append("</title>");
            sb.append("<link>").append(escapeXml(link)).append("</link>");
            sb.append("<description>").append(escapeXml(description)).append("</description>");
            sb.append("</item>");
        }

        sb.append("</channel></rss>");
        return sb.toString();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
