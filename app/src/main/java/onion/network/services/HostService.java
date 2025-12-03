package onion.network.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;

import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import onion.network.R;
import onion.network.TorManager;
import onion.network.call.CallManager;
import onion.network.models.WallBot;
import onion.network.clients.ChatClient;
import onion.network.tor.TorStatusFormatter;
import onion.network.servers.BlogServer;
import onion.network.servers.Server;
import onion.network.ui.views.RequestTool;

public class HostService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "service_channel";
    private static final String CHANNEL_NAME = "Service Channel";
    private static final String TAG = "onion.network:HostService";
    private static final long UNSENT_RESEND_PERIOD_MS = TimeUnit.HOURS.toMillis(1);
    private static final long TOR_RESTART_PERIOD_MS = TimeUnit.MINUTES.toMillis(10);
    private Timer timer;
    private TorManager torManager;
    private Server server;
    private BlogServer blogServer;
    private CallManager callManager;
    private PowerManager.WakeLock wakeLock;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastNotificationText = "";
    private final TorManager.LogListener torLogListener = line -> {
        TorStatusFormatter.Status status = TorStatusFormatter.parse(line);
        if (!status.hasChanged()) {
            return;
        }
        if (status.isReady()) {
            postNotificationUpdate(getString(R.string.notification_tor_ready));
        } else {
            String message = status.getMessage();
            if (message != null) {
                postNotificationUpdate(message);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Сервіс не прив'язується до активності
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundServiceWithNotification();
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
        server = Server.getInstance(this);
        blogServer = BlogServer.getInstance(this);
        torManager = TorManager.getInstance(this);
        if (torManager.isReady()) {
            postNotificationUpdate(getString(R.string.notification_tor_ready));
        }
        return START_STICKY;
    }

    private void startForegroundServiceWithNotification() {
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification createNotification() {
        ensureNotificationChannel();
        if (notificationBuilder == null) {
            notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_tor_title))
                    .setContentText(getString(R.string.notification_tor_initial))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            lastNotificationText = getString(R.string.notification_tor_initial);
        }
        return notificationBuilder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log("Service created");

        notificationManager = NotificationManagerCompat.from(this);
        ensureNotificationChannel();

        server = Server.getInstance(this);
        blogServer = BlogServer.getInstance(this);
        torManager = TorManager.getInstance(this);
        callManager = CallManager.getInstance(this);
        torManager.addLogListener(torLogListener);
        if (torManager.isReady()) {
            postNotificationUpdate(getString(R.string.notification_tor_ready));
        }

        // Ініціалізуємо таймер для періодичних задач
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                log("Updating unsent messages and requests");
                try {
                    ChatClient.getInstance(getApplicationContext()).sendUnsent();
                } catch (IOException e) {
                    log(e.getMessage());
                }
                RequestTool.getInstance(getApplicationContext()).sendAllRequests();
            }
        }, 0, UNSENT_RESEND_PERIOD_MS); // Оновлюємо раз на годину

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (callManager != null && callManager.hasActiveCall()) {
                    log("Skipping Tor restart during active call");
                    return;
                }
                if (torManager != null && torManager.isReady()) {
                    torManager.restartTor("scheduled reconnect");
                }
            }
        }, TOR_RESTART_PERIOD_MS, TOR_RESTART_PERIOD_MS);

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
        if (torManager != null) {
            torManager.removeLogListener(torLogListener);
        }

        super.onDestroy();
    }

    private void log(String message) {
        Log.i(getClass().getName(), message); // Логування з унікальним тегом
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
                if (existing == null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
                    manager.createNotificationChannel(channel);
                }
            }
        }
    }

    private void postNotificationUpdate(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        mainHandler.post(() -> updateNotificationText(text));
    }

    private void updateNotificationText(String text) {
        if (notificationBuilder == null || text.equals(lastNotificationText)) {
            return;
        }
        lastNotificationText = text;
        notificationBuilder.setContentText(text);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }
}
