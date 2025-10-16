package onion.network.helpers;

import static onion.network.helpers.Const.REQUEST_CODE_RUNTIME_PERMISSIONS;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import onion.network.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumSet;


public class PermissionHelper {

    private final Activity activity;
    private final PermissionListener permissionListener;
    private final EnumSet<PermissionRequest> permissionRequests;

    public PermissionHelper(Activity activity, PermissionListener listener) {
        this(activity, listener, EnumSet.of(PermissionRequest.MEDIA, PermissionRequest.NOTIFICATIONS));
    }

    public PermissionHelper(Activity activity, PermissionListener listener, EnumSet<PermissionRequest> requests) {
        this.activity = activity;
        this.permissionListener = listener;
        this.permissionRequests = requests == null || requests.isEmpty()
                ? EnumSet.of(PermissionRequest.MEDIA)
                : EnumSet.copyOf(requests);
    }

    public void requestPermissions() {
        if (!shouldRequestRuntimePermissions()) {
            permissionListener.onPermissionsGranted();
            return;
        }

        if (collectMissingPermissions().isEmpty()) {
            permissionListener.onPermissionsGranted();
        } else {
            showPermissionExplanationDialog();
        }
    }

    private boolean hasAllMediaPermissions() {
        if (!permissionRequests.contains(PermissionRequest.MEDIA)) {
            return true;
        }
        for (String permission : requiredMediaPermissions()) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasNotificationPermission() {
        if (!permissionRequests.contains(PermissionRequest.NOTIFICATIONS)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void showPermissionExplanationDialog() {
        String message = buildRationaleMessage();
        AlertDialog dialogPermissions = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                .setTitle("Permissions required")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Grant permissions", (dialog, which) -> requestPermissionsInternal())
                .setNegativeButton("Refuse", (dialog, which) -> permissionListener.onPermissionsDenied())
                .create();
        dialogPermissions.setOnShowListener(d -> {
            dialogPermissions.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
            dialogPermissions.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
        });
        dialogPermissions.show();
    }

    private void requestPermissionsInternal() {
        if (!shouldRequestRuntimePermissions()) {
            permissionListener.onPermissionsGranted();
            return;
        }

        List<String> permissions = collectMissingPermissions();
        if (permissions.isEmpty()) {
            permissionListener.onPermissionsGranted();
            return;
        }

        ActivityCompat.requestPermissions(activity,
                permissions.toArray(new String[0]),
                REQUEST_CODE_RUNTIME_PERMISSIONS);
    }

    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != REQUEST_CODE_RUNTIME_PERMISSIONS) {
            return;
        }

        if (arePermissionsGranted(grantResults) && collectMissingPermissions().isEmpty()) {
            permissionListener.onPermissionsGranted();
        } else {
            showPermissionExplanationDialog();
        }
    }

    private List<String> collectMissingPermissions() {
        List<String> permissions = new ArrayList<>();
        if (!hasAllMediaPermissions()) {
            Collections.addAll(permissions, requiredMediaPermissions());
        }
        if (permissionRequests.contains(PermissionRequest.CAMERA)) {
            addIfMissing(permissions, Manifest.permission.CAMERA);
        }
        if (permissionRequests.contains(PermissionRequest.MICROPHONE)) {
            addIfMissing(permissions, Manifest.permission.RECORD_AUDIO);
        }
        if (permissionRequests.contains(PermissionRequest.NOTIFICATIONS)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasNotificationPermission()) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions;
    }

    private String[] requiredMediaPermissions() {
        if (!permissionRequests.contains(PermissionRequest.MEDIA)) {
            return new String[0];
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
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

    private void addIfMissing(List<String> permissions, String permission) {
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
                && !permissions.contains(permission)) {
            permissions.add(permission);
        }
    }

    private boolean shouldRequestRuntimePermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    private String buildRationaleMessage() {
        List<String> reasons = new ArrayList<>();
        if (permissionRequests.contains(PermissionRequest.MEDIA)) {
            reasons.add("access your media files");
        }
        if (permissionRequests.contains(PermissionRequest.CAMERA)) {
            reasons.add("use the camera");
        }
        if (permissionRequests.contains(PermissionRequest.MICROPHONE)) {
            reasons.add("record audio");
        }
        if (permissionRequests.contains(PermissionRequest.NOTIFICATIONS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reasons.add("show notifications");
        }

        if (reasons.isEmpty()) {
            return "This permission is required.";
        }

        if (reasons.size() == 1) {
            return "This app requires permission to " + reasons.get(0) + '.';
        }

        StringBuilder builder = new StringBuilder("This app requires permission to ");
        for (int i = 0; i < reasons.size(); i++) {
            if (i == reasons.size() - 1) {
                builder.append("and ");
            }
            builder.append(reasons.get(i));
            if (i < reasons.size() - 2) {
                builder.append(", ");
            } else if (i == reasons.size() - 2) {
                builder.append(' ');
            }
        }
        builder.append('.');
        return builder.toString();
    }

    // Інтерфейс для зворотного виклику (callback)
    public interface PermissionListener {
        void onPermissionsGranted();

        void onPermissionsDenied();
    }

    public enum PermissionRequest {
        MEDIA,
        NOTIFICATIONS,
        CAMERA,
        MICROPHONE
    }
}
