package onion.network;

public class AppHeadless extends App {
    @Override
    public void onCreate() {
        super.onCreate();
        // Тут можна ініціалізувати headless-специфічні речі.
        ensureAuthToken(); // автогенерувати token якщо порожній
    }
}
