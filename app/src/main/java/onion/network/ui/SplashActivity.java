package onion.network.ui;

import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.EnumSet;

import onion.network.R;
import onion.network.databinding.ActivitySplashBinding;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.ThemeManager;

public class SplashActivity extends AppCompatActivity {

    private ActivitySplashBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.init(this).applyNoActionBarTheme(this);
        super.onCreate(savedInstanceState);

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestInitialPermissions();
    }

    // Обробка результатів запитів дозволів
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (PermissionHelper.handleOnRequestPermissionsResult(this, requestCode, permissions, grantResults)) {
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void requestInitialPermissions() {
        CharSequence rationale = getString(R.string.permission_initial_message);
        PermissionHelper.runWithPermissions(
                this,
                EnumSet.of(PermissionHelper.PermissionRequest.MEDIA, PermissionHelper.PermissionRequest.NOTIFICATIONS),
                rationale,
                this::onPermissionsGranted,
                this::showDeniedMessage
        );
    }

    private void onPermissionsGranted() {
        binding.webView.setVisibility(VISIBLE);
        String htmlData = "<html><head><style>"
                + "body { margin:0; padding:0; background-color:#000; display:flex; justify-content:center; align-items:center; height:100vh; }"
                + "img { max-width:360px; max-height:360px; width:auto; height:auto; }"
                + "</style></head>"
                + "<body><img src='file:///android_asset/animation.gif'/></body></html>";
        binding.webView.loadDataWithBaseURL("file:///android_asset/", htmlData, "text/html", "UTF-8", null);

        proceedWithAppFunctionality();
    }

    // Метод, що викликається після надання всіх дозволів
    private void proceedWithAppFunctionality() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            goToMainActivity();
        }, 7000);
    }

    private void showDeniedMessage() {
        // Показуємо повідомлення, якщо користувач відмовився від дозволів
    Toast.makeText(this, getString(R.string.error_permissions_required), Toast.LENGTH_SHORT).show();
        goToMainActivity();
    }

    public void goToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
