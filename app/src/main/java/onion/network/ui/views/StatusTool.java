

package onion.network.ui.views;

import android.content.Context;

import java.io.IOException;

import onion.network.ItemTask;
import onion.network.clients.HttpClient;

public class StatusTool {

    private static StatusTool instance;
    Context context;

    public StatusTool(Context c) {
        context = c;
    }

    synchronized public static StatusTool getInstance(Context context) {
        if (instance == null)
            instance = new StatusTool(context.getApplicationContext());
        return instance;
    }

    public boolean isOnline(String address) {
        try {
            String rs = HttpClient.get(new ItemTask(context, address, "name").getUrl());
            if (rs.isEmpty()) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }
        return true;
    }

}
