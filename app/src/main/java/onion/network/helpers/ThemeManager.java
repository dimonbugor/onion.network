package onion.network.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.TypedValue;

import onion.network.R;

public class ThemeManager {
    public final static String[] themes = {"Dark", "Light"};
    public final static String[] themeKeys = {"AppTheme.Dark", "AppTheme.Light"};
    private final String PREFS_NAME = "settings";
    private final String KEY_THEME = "theme";
    private static ThemeManager instance = null;
    private String currentTheme = null;
    private String currentNoActionBarTheme = null;

    private ThemeManager(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentTheme = prefs.getString(KEY_THEME, "AppTheme.Dark");
        currentNoActionBarTheme = currentTheme + ".NoActionBar";
    }

    public static ThemeManager init(Context context) {
        if(instance == null) {
            instance = new ThemeManager(context);
        }
        return instance;
    }

    public void applyTheme(Activity activity) {
        int themeId = activity.getResources().getIdentifier(getTheme(), "style", activity.getPackageName());
        activity.setTheme(themeId);
    }

    public void applyNoActionBarTheme(Activity activity) {
        int themeId = activity.getResources().getIdentifier(getNoActionBarTheme(), "style", activity.getPackageName());
        activity.setTheme(themeId);
    }

    public void setTheme(Context context, String themeName) {
        currentTheme = themeName;
        currentNoActionBarTheme = currentTheme + ".NoActionBar";
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, themeName)
                .apply();
    }

    public String getTheme() {
        return currentTheme == null ? "AppTheme.Dark" : currentTheme;
    }

    public String getNoActionBarTheme() {
        return currentNoActionBarTheme == null ? "AppTheme.Dark.NoActionBar" : currentNoActionBarTheme;
    }

    public static int getColor(Context context, int attr) {
        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(attr, typedValue, true);
        int color = typedValue.data;
        return color;
    }

    public static int getDialogThemeResId(Context context) {
        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(R.attr.alertDialogThemeCustom, typedValue, true);
        int dialogThemeResId = typedValue.resourceId;
        return dialogThemeResId;
    }
}
