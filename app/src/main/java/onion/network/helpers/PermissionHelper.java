package onion.network.helpers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import onion.network.R;

public final class PermissionHelper {

    private PermissionHelper() {
    }

    private static final AtomicInteger NEXT_REQUEST_CODE = new AtomicInteger(onion.network.helpers.Const.REQUEST_CODE_RUNTIME_PERMISSIONS);

    private static final android.util.SparseArray<PendingRequest> pendingRequests = new android.util.SparseArray<>();

    public static void runWithPermissions(@NonNull Activity activity,
                                           @NonNull EnumSet<PermissionRequest> requests,
                                           @NonNull Runnable onGranted,
                                           @Nullable Runnable onDenied) {
        runWithPermissions(activity, requests, null, onGranted, onDenied);
    }

    public static void runWithPermissions(@NonNull Activity activity,
                                           @NonNull EnumSet<PermissionRequest> requests,
                                           @Nullable CharSequence rationaleMessage,
                                           @NonNull Runnable onGranted,
                                           @Nullable Runnable onDenied) {
        EnumSet<PermissionRequest> reqSet = requests.isEmpty()
                ? EnumSet.noneOf(PermissionRequest.class)
                : EnumSet.copyOf(requests);

        String[] missing = collectMissingPermissions(activity, reqSet);
        if (missing.length == 0) {
            onGranted.run();
            return;
        }

        if (rationaleMessage != null) {
            showRationaleDialog(activity, rationaleMessage, () -> requestInternal(activity, reqSet, missing, onGranted, onDenied), onDenied);
        } else if (shouldShowRationale(activity, missing)) {
            showRationaleDialog(activity, rationaleMessage, () -> requestInternal(activity, reqSet, missing, onGranted, onDenied), onDenied);
        } else {
            requestInternal(activity, reqSet, missing, onGranted, onDenied);
        }
    }

    private static void requestInternal(Activity activity,
                                         EnumSet<PermissionRequest> requests,
                                         String[] missing,
                                         Runnable onGranted,
                                         Runnable onDenied) {
        int requestCode = NEXT_REQUEST_CODE.getAndIncrement();
        synchronized (pendingRequests) {
            pendingRequests.put(requestCode, new PendingRequest(activity, requests, onGranted, onDenied));
        }
        ActivityCompat.requestPermissions(activity, missing, requestCode);
    }

    private static boolean shouldShowRationale(Activity activity, String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                return true;
            }
        }
        return false;
    }

    private static void showRationaleDialog(Activity activity,
                                            CharSequence message,
                                            Runnable onPositive,
                                            @Nullable Runnable onNegative) {
        AlertDialog dialog = DialogHelper.themedBuilder(activity)
                .setTitle(R.string.permission_required_title)
                .setMessage(message)
                .setPositiveButton(R.string.permission_required_positive, (d, which) -> onPositive.run())
                .setNegativeButton(R.string.permission_required_negative, (d, which) -> {
                    if (onNegative != null) {
                        onNegative.run();
                    }
                })
                .setCancelable(false)
                .create();
        DialogHelper.show(dialog);
    }

    public static boolean handleOnRequestPermissionsResult(@NonNull Activity activity,
                                                            int requestCode,
                                                            @NonNull String[] permissions,
                                                            @NonNull int[] grantResults) {
        PendingRequest pending;
        synchronized (pendingRequests) {
            pending = pendingRequests.get(requestCode);
            if (pending == null) {
                return false;
            }
            pendingRequests.remove(requestCode);
        }

        boolean granted = grantResults.length > 0;
        if (granted) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }

        if (granted) {
            String[] stillMissing = collectMissingPermissions(activity, pending.requests);
            if (stillMissing.length == 0) {
                pending.onGranted.run();
            } else {
                if (pending.onDenied != null) {
                    pending.onDenied.run();
                }
            }
        } else if (pending.onDenied != null) {
            pending.onDenied.run();
        }
        return true;
    }

    private static String[] collectMissingPermissions(Activity activity, EnumSet<PermissionRequest> requests) {
        if (requests.isEmpty()) {
            return new String[0];
        }
        List<String> missing = new ArrayList<>();
        if (requests.contains(PermissionRequest.MEDIA)) {
            addMediaPermissions(activity, missing);
        }
        if (requests.contains(PermissionRequest.CAMERA)) {
            addIfMissing(activity, missing, android.Manifest.permission.CAMERA);
        }
        if (requests.contains(PermissionRequest.MICROPHONE)) {
            addIfMissing(activity, missing, android.Manifest.permission.RECORD_AUDIO);
        }
        if (requests.contains(PermissionRequest.NOTIFICATIONS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(activity, missing, android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing.toArray(new String[0]);
    }

    private static void addMediaPermissions(Activity activity, List<String> missing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(activity, missing, android.Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(activity, missing, android.Manifest.permission.READ_MEDIA_VIDEO);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addIfMissing(activity, missing, android.Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            addIfMissing(activity, missing, android.Manifest.permission.READ_EXTERNAL_STORAGE);
            addIfMissing(activity, missing, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private static void addIfMissing(Activity activity, List<String> missing, String permission) {
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            if (!missing.contains(permission)) {
                missing.add(permission);
            }
        }
    }

    private static final class PendingRequest {
        final EnumSet<PermissionRequest> requests;
        final Runnable onGranted;
        final @Nullable Runnable onDenied;

        PendingRequest(Activity activity,
                       EnumSet<PermissionRequest> requests,
                       Runnable onGranted,
                       Runnable onDenied) {
            this.requests = requests;
            this.onGranted = () -> activity.runOnUiThread(onGranted);
            this.onDenied = onDenied == null ? null : () -> activity.runOnUiThread(onDenied);
        }
    }

    public enum PermissionRequest {
        MEDIA,
        NOTIFICATIONS,
        CAMERA,
        MICROPHONE
    }
}
