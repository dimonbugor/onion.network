

package onion.network.models;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

import org.json.JSONObject;

import onion.network.TorManager;
import onion.network.clients.ChatClient;
import onion.network.clients.HttpClient;
import onion.network.databases.ChatDatabase;
import onion.network.models.AgentLoop;
import onion.network.models.MemoryStream;
import onion.network.settings.Settings;
import onion.network.ui.views.StatusTool;

public class ChatBot {

    private static ChatBot instance;
    String TAG = "ChatBot";
    Context context;

    public ChatBot(Context context) {
        this.context = context;
        AgentLoop.getInstance(context).start();
    }

    synchronized public static ChatBot getInstance(Context context) {
        if (instance == null)
            instance = new ChatBot(context.getApplicationContext());
        return instance;
    }

    void log(String s) {
        Log.i(TAG, s);
    }

    public String addr() {
        if (!Settings.getPrefs(context).getBoolean("chatbot", false)) {
            return null;
        }
        String addr = Settings.getPrefs(context).getString("chatbotaddr", "");
        if (addr == null || addr.trim().isEmpty()) {
            return null;
        }
        return addr;
    }


    public boolean handle(final String address, final String name, final String message, final long time, final boolean saveResponse) {

        final String addr = addr();
        if (addr == null) return false;

        if (!StatusTool.getInstance(context).isOnline(address)) {
            log("chat partner offline");
            return false;
        }

        new Thread() {
            @Override
            public void run() {

                // Локальна відповідь без зовнішніх викликів
                final String response = buildLocalResponse(address, name, message, time);
                MemoryStream.appendEvent(context, "HEAR", new JSONObject()
                        .put("text", message == null ? "" : message)
                        .put("from", address)
                        .put("ts", time));

                // send message
                String respstr = null;
                try {
                    respstr = HttpClient.get(ChatClient.getInstance(context).makeUri(
                            TorManager.getInstance(context).getID(),
                            address,
                            response,
                            Math.max(time + 100, System.currentTimeMillis()
                            )));
                } catch (IOException ex) {
                    log("failed to send response message");
                }

                // read response
                boolean sendSuccess = "1".equals(respstr);

                // handle response
                if (sendSuccess) {
                    log("response sent");
                if (saveResponse) {
                        long t = Math.max(time + 100, System.currentTimeMillis());
                        ChatDatabase.getInstance(context).addMessage(
                                TorManager.getInstance(context).getID(),
                                address,
                                response,
                                t,
                                false,
                                false
                        );
                        log("response saved to db");
                        MemoryStream.appendEvent(context, "SAY", new JSONObject()
                                .put("reply_to", message == null ? "" : message)
                                .put("text", response)
                                .put("date", System.currentTimeMillis())
                                .put("addr", TorManager.getInstance(context).getID()));
                    }
                } else {
                    log("failed to send response");
                }

                //return sendSuccess;
            }
        }.start();

        return true;

    }

    private String buildLocalResponse(String address, String name, String message, long time) {
        StringBuilder sb = new StringBuilder();
        sb.append("Автовідповідь");
        if (name != null && !name.trim().isEmpty()) {
            sb.append(" для ").append(name.trim());
        }
        sb.append(": ");
        if (message != null && !message.trim().isEmpty()) {
            sb.append("отримано \"").append(message.trim()).append("\"");
        } else {
            sb.append("отримано повідомлення.");
        }
        return sb.toString();
    }

}
