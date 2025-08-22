/*
 * Blog.onion
 *
 * http://play.google.com/store/apps/details?id=onion.blog
 * http://onionapps.github.io/Blog.onion/
 * http://github.com/onionApps/Blog.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network.servers;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;

import onion.network.models.Blog;
import onion.network.models.Response;

public class BlogServer {
    private Context context;
    private static BlogServer instance;
    private Blog blog;
    private String TAG = "BlogServer";

    public BlogServer(Context context) {
        this.context = context;
        this.blog = Blog.getInstance(context);
    }

    public static BlogServer getInstance(Context context) {
        if (instance == null) {
            instance = new BlogServer(context);
        }
        return instance;
    }

    private void log(String s) {
        Log.i(TAG, s);
    }

    private void handle(InputStream is, OutputStream os) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(is));
        ArrayList<String> headers = new ArrayList<>();
        while (true) {
            String header = r.readLine();
            if (header == null || header.trim().length() == 0) {
                break;
            }
            headers.add(header);
        }

        if (headers.size() == 0) {
            return;
        }

        for (String header : headers) {
            log("Header " + header);
        }

        String[] rr = headers.get(0).split(" ");

        for (String rrr : rr) {
            log("Req " + rrr);
        }

        if (!rr[2].startsWith("HTTP/")) {
            log("Invalid protocol");
            return;
        }

        if (!"GET".equals(rr[0]) && !"HEAD".equals(rr[0])) {
            log("Invalid method");
            return;
        }

        String path = rr[1];

        Response response = blog.getResponse(path, false);
        os.write(("HTTP/1.0 " + response.getStatusCode() + " " + response.getStatusString() + "\r\n").getBytes());
        os.write(("Content-Length: " + response.getContent().length + "\r\n").getBytes());
        os.write(("Connection: close\r\n").getBytes());
        if (response.getContentType() != null && response.getCharset() != null) {
            os.write(("Content-Type: " + response.getContentType() + "; charset=" + response.getCharset() + "\r\n").getBytes());
        } else if (response.getContentType() != null) {
            os.write(("Content-Type: " + response.getContentType() + "\r\n").getBytes());
        }
        if (response.getContentType() != null && !response.getContentType().equals("text/html")) {
            os.write("Cache-Control: max-age=31556926\r\n".getBytes());
        }
        os.write(("\r\n").getBytes());
        os.write(response.getContent());
        os.flush();
    }

    private void handle() {
        LocalSocket ls = Server.getInstance(context).getLs();
        InputStream is = null;
        OutputStream os = null;

        try {
            is = ls.getInputStream();
        } catch (IOException ex) {
        }

        try {
            os = ls.getOutputStream();
        } catch (IOException ex) {
        }

        if (is != null && os != null) {
            try {
                handle(is, os);
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }

        if (is != null) {
            try {
                is.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        if (os != null) {
            try {
                os.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}
