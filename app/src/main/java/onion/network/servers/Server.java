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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import onion.network.FriendTool;
import onion.network.Item;
import onion.network.Site;
import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.models.ItemResult;
import onion.network.settings.Settings;
import onion.network.ui.views.RequestTool;

public class Server {

    private static Server instance;
    Context context;
    String socketName;
    HttpServer httpServer;
    LocalSocket ls;
    LocalServerSocket lss;
    private String TAG = "Server";

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
                ItemResult data = ItemDatabase.getInstance(context).get(type, index, count);
                if (data.size() == 0 && "name".equals(type) && "".equals(index) && count == 1) {
                    List<Item> il = new ArrayList<>();
                    il.add(new Item("name", "", "", new JSONObject()));
                    data = new ItemResult(il, null, true, false);
                }
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
            return;
        }

        if (uri.getPath().equals("/i")) {
            String type = uri.getQueryParameter("t");
            ItemResult data = ItemDatabase.getInstance(context).get(type, "", 1);
            response.setContent(data.one().data());
            return;
        }

        if (uri.getPath().equals("/m")) {
            response.setContentPlain(ChatServer.getInstance(context).handle(uri) ? "1" : "0");
            return;
        }

        if(uri.getPath().equals("/r")) {
            response.setContentPlain(FriendTool.getInstance(context).handleUpdate(uri) ? "1" : "0");
            return;
        }

    }

    public String getSocketName() {
        return socketName;
    }

}
