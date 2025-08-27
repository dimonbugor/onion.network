package onion.network.models;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import onion.network.R;
import onion.network.helpers.ThemeManager;
import onion.network.settings.Settings;
import onion.network.ui.MainActivity;

public class Notifier {

    private static Notifier instance;
    private static final String CHANNEL_ID = "onion_network_channel";
    private Context context;

    private Notifier(Context context) {
        this.context = context.getApplicationContext();
        createNotificationChannel(); // Create notification channel
    }

    synchronized public static Notifier getInstance(Context context) {
        if (instance == null) {
            instance = new Notifier(context);
        }
        return instance;
    }

    private void log(String s) {
        Log.i("Notifier", s);
    }

    public void clr() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(5); // Use a unique ID for each notification
    }

    long lastSoundTime = 0;

    private void msgSound() {
        boolean sound = Settings.getPrefs(context).getBoolean("sound", false);
        if (!sound) return;
        long soundTime = System.currentTimeMillis();
        if (lastSoundTime + 700 > soundTime) return;
        lastSoundTime = soundTime;
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(context, notification);
            r.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void msgNotify() {
        boolean sound = Settings.getPrefs(context).getBoolean("sound", false);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setColor(ThemeManager.getColor(context, android.R.attr.colorBackground))
                .setContentTitle(context.getResources().getString(R.string.app_name))
                .setContentText("New Message")
                .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class).putExtra("page", "chat"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT); // Set priority for Android 8.0+

        if (sound) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        }

        notificationManager.notify(5, b.build());
    }

    public void msg(String sender) {
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity != null) {
            if (mainActivity.address.isEmpty() || mainActivity.address.equals(sender)) {
                msgSound();
                mainActivity.blink(R.drawable.ic_question_answer);
            } else {
                msgNotify();
            }
        } else {
            msgNotify();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Onion Network Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}