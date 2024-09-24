package onion.network.helpers;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import onion.network.R;


public class PermissionHelper {

    private static final int REQUEST_CODE_STORAGE = 100;
    private static final int REQUEST_CODE_NOTIFICATION = 101;

    private final Activity activity;
    private final PermissionListener permissionListener;

    public PermissionHelper(Activity activity, PermissionListener listener) {
        this.activity = activity;
        this.permissionListener = listener;
    }

    // Метод для запиту дозволів
    public void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasStoragePermission() || !hasNotificationPermission()) {
                showPermissionExplanationDialog();
            } else {
                // Якщо всі дозволи надано, викликаємо onPermissionsGranted
                permissionListener.onPermissionsGranted();
            }
        } else {
            // Якщо версія SDK нижче M, дозволи не потрібні, тому одразу викликаємо onPermissionsGranted
            permissionListener.onPermissionsGranted();
        }
    }

    private boolean hasStoragePermission() {
        int readPermission = ContextCompat.checkSelfPermission(activity,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? Manifest.permission.READ_MEDIA_IMAGES
                        : Manifest.permission.READ_EXTERNAL_STORAGE);
        return readPermission == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void showPermissionExplanationDialog() {
        new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle("Permissions required")
                .setMessage("This app requires permissions to access files and show notifications.")
                .setCancelable(false)
                .setPositiveButton("Grant permissions", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestPermissionsInternal();
                    }
                })
                .setNegativeButton("Refuse", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        permissionListener.onPermissionsDenied();
                    }
                })
                .show();
    }

    private void requestPermissionsInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissionsToRequest = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest = new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                };
            }

            ActivityCompat.requestPermissions(activity, permissionsToRequest, REQUEST_CODE_STORAGE);
        }
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_STORAGE, REQUEST_CODE_NOTIFICATION:
                if (arePermissionsGranted(grantResults)) {
                    permissionListener.onPermissionsGranted();
                } else {
                    showPermissionExplanationDialog();
                }
                break;
        }
    }

    private boolean arePermissionsGranted(int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Інтерфейс для зворотного виклику (callback)
    public interface PermissionListener {
        void onPermissionsGranted();

        void onPermissionsDenied();
    }
}