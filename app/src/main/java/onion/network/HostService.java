package onion.network;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

import fi.iki.elonen.NanoHTTPD;
import onion.network.clients.ChatClient;
import onion.network.clients.HttpClient;
import onion.network.helpers.Utils;
import onion.network.servers.Server;
import onion.network.ui.views.RequestTool;

public class HostService extends Service {
    private static final String TAG = "onion.network:HostService";
    private Timer timer;
    private TorManager torManager;
    private ChatClient chatClient;
    private Server server;
    private PowerManager.WakeLock wakeLock;
    private HttpServer httpServer;

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
        checkTor();
        startHttpServer(); // Запускаємо HTTP сервер
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

        server = Server.getInstance(this);
        torManager = torManager.getInstance(this);
        chatClient = ChatClient.getInstance(this);

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
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }

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

    public void checkTor() {
        Handler handler = new Handler(Looper.myLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean isRunning = isTorRunning();
                // Повертаємося на основний потік, щоб оновити UI
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isRunning) {
                            Log.d("TorCheck", "Tor is running");
                        } else {
                            Log.d("TorCheck", "Tor is not running");
                        }
                    }
                });
            }
        }).start();
    }

    public boolean isTorRunning() {
        try {
            // Підключення до Tor на локальному хості
            Socket socket = new Socket("127.0.0.1", 9050);
            socket.close(); // Закриття сокету, якщо з'єднання успішне
            return true; // Tor працює
        } catch (IOException e) {
            return false; // Tor не працює
        }
    }

    private void startHttpServer() {
        httpServer = new HttpServer(8080); // Використовуємо порт 8080
        try {
            httpServer.start();
            log("HTTP Server started on port 8080");
        } catch (IOException e) {
            log("Failed to start HTTP server: " + e.getMessage());
        }
    }

    public class HttpServer extends NanoHTTPD {

        public HttpServer(int port) {
            super(port);
        }

        @Override
        public Response serve(NanoHTTPD.IHTTPSession session) {
            String uri = session.getUri(); // Отримуємо URI запиту
            String responseMsg;
            if(uri.equals("/")) {
                responseMsg = "<html><body><h1>hello from tor!</h1></body></html>";
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", responseMsg);
            }
            try {
                // Використовуємо HttpClient для обробки запиту
                byte[] content = HttpClient.getbin(uri, true, false, 0); // Передаємо через Tor
                responseMsg = new String(content, Utils.UTF_8); // Конвертуємо в рядок
            } catch (IOException e) {
                responseMsg = "<html><body><h1>Error: " + e.getMessage() + "</h1></body></html>";
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", responseMsg);
            }

            // Повертаємо успішну відповідь
            return newFixedLengthResponse(Response.Status.OK, "text/html", responseMsg);
        }
    }
}