package onion.network;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

public class HostService extends Service {

    // Змінили тег на унікальний з префіксом
    private static final String TAG = "onion.network:HostService";
    private Timer timer;
    private Server server;
    private TorManager torManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Сервіс не прив'язується до активності
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        server = Server.getInstance(this);
        torManager = TorManager.getInstance(this);
        checkTor();
        return START_STICKY; // Сервіс перезапускається після зупинки
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log("Service created");

        // Отримуємо WakeLock для запобігання відключення
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire(); // Отримуємо WakeLock
        }

        // Ініціалізуємо таймер для періодичних задач
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                log("Updating unsent messages and requests");
                ChatClient.getInstance(getApplicationContext()).sendUnsent();
                RequestTool.getInstance(getApplicationContext()).sendAllRequests();
            }
        }, 0, 1000 * 60 * 60); // Оновлюємо раз на годину

        WallBot.getInstance(this); // Ініціалізація WallBot
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

        super.onDestroy();
    }

    private void log(String message) {
        Log.i(TAG, message); // Логування з унікальним тегом
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
}