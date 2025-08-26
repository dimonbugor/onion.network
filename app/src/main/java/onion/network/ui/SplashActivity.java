package onion.network.ui;

import static android.view.View.VISIBLE;

import android.content.Intent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import onion.network.databinding.ActivitySplashBinding;
import onion.network.helpers.PermissionHelper;

public class SplashActivity extends AppCompatActivity {

    private ActivitySplashBinding binding;
    private PermissionHelper permissionHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Ініціалізація PermissionHelper і передаємо callback для обробки дозволів
        permissionHelper = new PermissionHelper(this, new PermissionHelper.PermissionListener() {
            @Override
            public void onPermissionsGranted() {
                // Тут продовжуємо роботу після надання всіх дозволів
                binding.fullscreenContent.setVisibility(VISIBLE);
                proceedWithAppFunctionality();
            }

            @Override
            public void onPermissionsDenied() {
                // Якщо користувач відмовився надати дозволи
                showDeniedMessage();
            }
        });

        // Запит дозволів
        permissionHelper.requestPermissions();
    }

    // Обробка результатів запитів дозволів
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionHelper.onRequestPermissionsResult(requestCode, grantResults);
    }

    // Метод, що викликається після надання всіх дозволів
    private void proceedWithAppFunctionality() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            goToMainActivity();
        }, 2000);
    }

    private void showDeniedMessage() {
        // Показуємо повідомлення, якщо користувач відмовився від дозволів
        Toast.makeText(this, "Error: Permissions required", Toast.LENGTH_SHORT).show();
        goToMainActivity();
    }

    public void goToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}