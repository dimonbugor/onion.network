package onion.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Timer;
import java.util.TimerTask;

import onion.network.clients.ChatClient;
import onion.network.servers.Server;
import onion.network.ui.views.RequestTool;

public class HostService extends Service {
    private static final String TAG = "onion.network:HostService";
    private Timer timer;
    private TorManager torManager;
    private Server server;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Сервіс не прив'язується до активності
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundServiceWithNotification();
        } else {
            startForeground(1, createNotification());
        }
        torManager = TorManager.getInstance(this);
        server = Server.getInstance(this);
        return START_STICKY;
    }

    private void startForegroundServiceWithNotification() {
        Notification notification = createNotification();
        startForeground(1, notification);
    }

    private Notification createNotification() {
        NotificationChannel channel = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = new NotificationChannel(
                    "service_channel", "Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, "service_channel")
                .setContentTitle("Service Running")
                .setContentText("Tor service is active")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log("Service created");

        torManager = torManager.getInstance(this);
        server = Server.getInstance(this);

        // Ініціалізуємо таймер для періодичних задач
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                log("Updating unsent messages and requests");
                ChatClient.getInstance(getApplicationContext()).sendUnsent();
                RequestTool.getInstance(getApplicationContext()).sendAllRequests();
            }
        }, 0, 1000 * 60 * 60); // Оновлюємо раз на годину

        WallBot.getInstance(this); // Ініціалізація WallBot

        // Отримуємо WakeLock для запобігання відключення
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire(); // Отримуємо WakeLock
        }
    }

    @Override
    public void onDestroy() {
        log("Service destroyed");

        // Зупиняємо HTTP сервер
        server.stopHttpServer();

        // Скасовуємо таймер
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }

        // Звільняємо WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        super.onDestroy();
    }

    private void log(String message) {
        Log.i(getClass().getName(), message); // Логування з унікальним тегом
    }
}