package onion.network.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;

import onion.network.R;
import onion.network.databases.ItemDatabase;
import onion.network.models.Item;
import onion.network.helpers.Utils;

/**
 * Headless сервіс, що кожні ~30с пише коротке відео (CameraX) у внутрішнє сховище і публікує як post.
 */
public class VideoCaptureService extends Service implements LifecycleOwner {

    private static final String TAG = "VideoCaptureService";
    private static final String CHANNEL_ID = "video_capture_channel";
    private static final int NOTIFICATION_ID = 42;
    private static final long INTERVAL_MS = 30_000L;
    private static final long ORDER_BASE = 100000000000000L;

    private LifecycleRegistry lifecycleRegistry;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Timer timer;
    private Recording currentRecording;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(LifecycleRegistry.State.CREATED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        if (!hasPermissions()) {
            Log.w(TAG, "Missing camera/audio permission; stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        bindCameraProvider();
        return START_STICKY;
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void bindCameraProvider() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                startCamera();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProvider error: " + e.getMessage());
                stopSelf();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startCamera() {
        if (cameraProvider == null) return;
        Recorder recorder = new Recorder.Builder().build();
        videoCapture = VideoCapture.withOutput(recorder);

        Preview preview = new Preview.Builder().build();
        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            lifecycleRegistry.setCurrentState(LifecycleRegistry.State.RESUMED);
            startSchedule();
        } catch (Exception ex) {
            Log.e(TAG, "bindToLifecycle failed: " + ex.getMessage());
            stopSelf();
        }
    }

    private void startSchedule() {
        stopSchedule();
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                recordOnce();
            }
        }, 0, INTERVAL_MS);
    }

    private void stopSchedule() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

    private void recordOnce() {
        if (videoCapture == null) return;
        File file = createFile();
        FileOutputOptions options = FileOutputOptions.builder(file).build();
        currentRecording = videoCapture.getOutput()
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                    switch (videoRecordEvent.getClass().getSimpleName()) {
                        case "Finalized":
                            publishVideo(file);
                            break;
                        default:
                            // ignore others
                    }
                });
        // зупинити через 5с
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (currentRecording != null) {
                        currentRecording.stop();
                    }
                } catch (Exception ignored) {
                }
            }
        }, 5000);
    }

    private File createFile() {
        File dir = new File(getFilesDir(), "capture");
        if (!dir.exists()) dir.mkdirs();
        String name = "clip_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4";
        return new File(dir, name);
    }

    private void publishVideo(File file) {
        try {
            byte[] data = Utils.readFileAsBytes(file);
            String eventId = Utils.sha1Digest(file.getName() + "|" + data.length);
            long ts = System.currentTimeMillis();
            long idx = ORDER_BASE - ts;
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("event_id", eventId);
            payload.put("type", "video");
            payload.put("path", file.getAbsolutePath());
            payload.put("size", data.length);
            payload.put("date", ts);
            ItemDatabase.getInstance(this).put(new Item("post", eventId, "" + idx, payload));
            Log.i(TAG, "video published " + file.getAbsolutePath());
        } catch (Exception ex) {
            Log.w(TAG, "publishVideo failed: " + ex.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        stopSchedule();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        super.onDestroy();
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Video Capture", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Headless capture")
                .setContentText("Recording short clips")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    @NonNull
    @Override
    public LifecycleRegistry getLifecycle() {
        return lifecycleRegistry;
    }
}
