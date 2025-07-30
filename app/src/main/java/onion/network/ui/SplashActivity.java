package onion.network.ui;

import android.content.Intent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import onion.network.databinding.ActivitySplashBinding;
import onion.network.helpers.PermissionHelper;

public class SplashActivity extends AppCompatActivity {

    private static final int MAX_LAUNCH_COUNT = 10;
    private static final String PREF_NAME = "app_prefs";
    private static final String LAUNCH_COUNT_KEY = "launch_count";

    private ActivitySplashBinding binding;
    private PermissionHelper permissionHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Перевірка ліміту запусків
//        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
//        int launchCount = prefs.getInt(LAUNCH_COUNT_KEY, 0);
//
//        if (launchCount >= MAX_LAUNCH_COUNT) {
//            Toast.makeText(this, "Тестовий період завершено. Дякуємо!", Toast.LENGTH_LONG).show();
//            finish(); // Закриваємо додаток
//            return;
//        }
//
//        // Збільшуємо лічильник і зберігаємо
//        prefs.edit().putInt(LAUNCH_COUNT_KEY, launchCount + 1).apply();

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Ініціалізація PermissionHelper і передаємо callback для обробки дозволів
        permissionHelper = new PermissionHelper(this, new PermissionHelper.PermissionListener() {
            @Override
            public void onPermissionsGranted() {
                // Тут продовжуємо роботу після надання всіх дозволів
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
        // Тут продовжуйте вашу логіку
        goToMainActivity();
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