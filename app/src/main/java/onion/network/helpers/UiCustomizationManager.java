package onion.network.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import onion.network.R;
import onion.network.settings.Settings;

public final class UiCustomizationManager {

    private static final String PREF_CALL_FAB_POSITION = "ui_call_fab_position";
    private static final String PREF_MENU_BUTTON_POSITION = "ui_menu_button_position";
    private static final String PREF_CHAT_COMPOSER_STYLE = "ui_chat_composer_style";
    private static final String PREF_FRIEND_CARD_STYLE = "ui_friend_card_style";
    private static final String PREF_COLOR_PRESET = "ui_color_preset";

    private UiCustomizationManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return Settings.getPrefs(context.getApplicationContext());
    }

    public enum FabPosition {
        BOTTOM_END("bottom_end"),
        BOTTOM_START("bottom_start"),
        TOP_END("top_end"),
        TOP_START("top_start"),
        CENTER_BOTTOM("center_bottom"),
        CENTER_TOP("center_top");

        private final String key;

        FabPosition(String key) {
            this.key = key;
        }

        public static FabPosition fromValue(String value) {
            for (FabPosition position : values()) {
                if (position.key.equals(value)) {
                    return position;
                }
            }
            return BOTTOM_END;
        }

        public String getKey() {
            return key;
        }
    }

    public static FabPosition getCallFabPosition(Context context) {
        String value = prefs(context).getString(PREF_CALL_FAB_POSITION, FabPosition.BOTTOM_END.getKey());
        return FabPosition.fromValue(value);
    }

    public static FabPosition getMenuButtonPosition(Context context) {
        String value = prefs(context).getString(PREF_MENU_BUTTON_POSITION, "bottom_left");
        switch (value) {
            case "bottom_right":
                return FabPosition.BOTTOM_END;
            case "bottom_center":
                return FabPosition.CENTER_BOTTOM;
            default:
                return FabPosition.BOTTOM_START;
        }
    }

    public enum ChatComposerStyle {
        DEFAULT("default"),
        COMPACT("compact"),
        EXPANDED("expanded"),
        LEFT_ALIGNED("left_aligned");

        private final String key;

        ChatComposerStyle(String key) {
            this.key = key;
        }

        public static ChatComposerStyle fromValue(String value) {
            for (ChatComposerStyle style : values()) {
                if (style.key.equals(value)) {
                    return style;
                }
            }
            return DEFAULT;
        }
    }

    public static ChatComposerStyle getChatComposerStyle(Context context) {
        String value = prefs(context).getString(PREF_CHAT_COMPOSER_STYLE, ChatComposerStyle.DEFAULT.key);
        return ChatComposerStyle.fromValue(value);
    }

    public static class ChatComposerConfig {
        public final int heightPx;
        public final int marginStartPx;
        public final int marginEndPx;
        public final int marginBottomPx;
        public final int paddingHorizontalPx;
        public final float textSizeSp;
        public final int bubblePaddingHorizontalPx;
        public final int bubblePaddingVerticalPx;
        public final float messageTextSizeSp;
        public final float metadataTextSizeSp;
        public final float messageLineSpacingMultiplier;
        public final boolean metadataStacked;
        public final boolean metadataAlignStart;
        public final int metadataSpacingVerticalPx;

        private ChatComposerConfig(int heightPx, int marginStartPx, int marginEndPx, int marginBottomPx,
                                   int paddingHorizontalPx, float textSizeSp,
                                   int bubblePaddingHorizontalPx, int bubblePaddingVerticalPx,
                                   float messageTextSizeSp, float metadataTextSizeSp,
                                   float messageLineSpacingMultiplier,
                                   boolean metadataStacked, boolean metadataAlignStart,
                                   int metadataSpacingVerticalPx) {
            this.heightPx = heightPx;
            this.marginStartPx = marginStartPx;
            this.marginEndPx = marginEndPx;
            this.marginBottomPx = marginBottomPx;
            this.paddingHorizontalPx = paddingHorizontalPx;
            this.textSizeSp = textSizeSp;
            this.bubblePaddingHorizontalPx = bubblePaddingHorizontalPx;
            this.bubblePaddingVerticalPx = bubblePaddingVerticalPx;
            this.messageTextSizeSp = messageTextSizeSp;
            this.metadataTextSizeSp = metadataTextSizeSp;
            this.messageLineSpacingMultiplier = messageLineSpacingMultiplier;
            this.metadataStacked = metadataStacked;
            this.metadataAlignStart = metadataAlignStart;
            this.metadataSpacingVerticalPx = metadataSpacingVerticalPx;
        }
    }

    public static ChatComposerConfig getChatComposerConfig(Context context) {
        ChatComposerStyle style = getChatComposerStyle(context);
        switch (style) {
            case COMPACT:
                return new ChatComposerConfig(
                        dpToPx(context, 44), dpToPx(context, 64), dpToPx(context, 10),
                        dpToPx(context, 10), dpToPx(context, 8), 10.8f,
                        dpToPx(context, 8), dpToPx(context, 4),
                        11f, 8.5f, 0.96f,
                        false, false, dpToPx(context, 1));
            case EXPANDED:
                return new ChatComposerConfig(
                        dpToPx(context, 72), dpToPx(context, 48), dpToPx(context, 12),
                        dpToPx(context, 20), dpToPx(context, 16), 14.5f,
                        dpToPx(context, 18), dpToPx(context, 12),
                        15.5f, 11f, 1.2f,
                        false, false, dpToPx(context, 4));
            case LEFT_ALIGNED:
                return new ChatComposerConfig(
                        dpToPx(context, 56), dpToPx(context, 16), dpToPx(context, 16),
                        dpToPx(context, 16), dpToPx(context, 12), 12.5f,
                        dpToPx(context, 12), dpToPx(context, 8),
                        13f, 10f, 1.05f,
                        true, true, dpToPx(context, 4));
            case DEFAULT:
            default:
                return new ChatComposerConfig(
                        dpToPx(context, 52), dpToPx(context, 88), dpToPx(context, 14),
                        dpToPx(context, 14), dpToPx(context, 12), 12f,
                        dpToPx(context, 12), dpToPx(context, 7),
                        12.5f, 9.5f, 1.04f,
                        false, false, dpToPx(context, 2));
        }
    }

    public enum FriendCardStyle {
        DEFAULT("default"),
        COMPACT("compact"),
        PILL("pill");

        private final String key;

        FriendCardStyle(String key) {
            this.key = key;
        }

        public static FriendCardStyle fromValue(String value) {
            for (FriendCardStyle style : values()) {
                if (style.key.equals(value)) {
                    return style;
                }
            }
            return DEFAULT;
        }
    }

    public static FriendCardStyle getFriendCardStyle(Context context) {
        String value = prefs(context).getString(PREF_FRIEND_CARD_STYLE, FriendCardStyle.DEFAULT.key);
        return FriendCardStyle.fromValue(value);
    }

    public static class FriendCardConfig {
        public final float cornerRadiusPx;
        public final int horizontalPaddingPx;
        public final int verticalPaddingPx;
        public final float nameTextSizeSp;
        public final float addressTextSizeSp;
        public final int avatarSizePx;

        private FriendCardConfig(float cornerRadiusPx, int horizontalPaddingPx, int verticalPaddingPx,
                                 float nameTextSizeSp, float addressTextSizeSp, int avatarSizePx) {
            this.cornerRadiusPx = cornerRadiusPx;
            this.horizontalPaddingPx = horizontalPaddingPx;
            this.verticalPaddingPx = verticalPaddingPx;
            this.nameTextSizeSp = nameTextSizeSp;
            this.addressTextSizeSp = addressTextSizeSp;
            this.avatarSizePx = avatarSizePx;
        }
    }

    public static FriendCardConfig getFriendCardConfig(Context context) {
        FriendCardStyle style = getFriendCardStyle(context);
        switch (style) {
            case COMPACT:
                return new FriendCardConfig(dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 6),
                        13f, 11f, dpToPx(context, 40));
            case PILL:
                return new FriendCardConfig(dpToPx(context, 28), dpToPx(context, 20), dpToPx(context, 12),
                        15.5f, 12.5f, dpToPx(context, 56));
            case DEFAULT:
            default:
                return new FriendCardConfig(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 8),
                        14f, 12f, dpToPx(context, 48));
        }
    }

    public enum ColorPreset {
        SYSTEM("system"),
        MIDNIGHT("midnight"),
        FOREST("forest"),
        SUNSET("sunset");

        private final String key;

        ColorPreset(String key) {
            this.key = key;
        }

        public static ColorPreset fromValue(String value) {
            for (ColorPreset preset : values()) {
                if (preset.key.equals(value)) {
                    return preset;
                }
            }
            return SYSTEM;
        }

        @ColorInt
        public int getAccentColor(Context context) {
            switch (this) {
                case MIDNIGHT:
                    return ContextCompat.getColor(context, R.color.ui_preset_midnight_accent);
                case FOREST:
                    return ContextCompat.getColor(context, R.color.ui_preset_forest_accent);
                case SUNSET:
                    return ContextCompat.getColor(context, R.color.ui_preset_sunset_accent);
                case SYSTEM:
                default:
                    return resolveAttrColor(context, com.google.android.material.R.attr.colorPrimaryContainer);
            }
        }

        @ColorInt
        public int getSurfaceColor(Context context) {
            switch (this) {
                case MIDNIGHT:
                    return ContextCompat.getColor(context, R.color.ui_preset_midnight_surface);
                case FOREST:
                    return ContextCompat.getColor(context, R.color.ui_preset_forest_surface);
                case SUNSET:
                    return ContextCompat.getColor(context, R.color.ui_preset_sunset_surface);
                case SYSTEM:
                default:
                    return resolveAttrColor(context, com.google.android.material.R.attr.colorSurface);
            }
        }

        @ColorInt
        public int getOnSurfaceColor(Context context) {
            switch (this) {
                case MIDNIGHT:
                    return ContextCompat.getColor(context, R.color.ui_preset_midnight_on_surface);
                case FOREST:
                    return ContextCompat.getColor(context, R.color.ui_preset_forest_on_surface);
                case SUNSET:
                    return ContextCompat.getColor(context, R.color.ui_preset_sunset_on_surface);
                case SYSTEM:
                default:
                    return resolveAttrColor(context, com.google.android.material.R.attr.colorOnSurface);
            }
        }

        @ColorInt
        public int getOnAccentColor(Context context) {
            switch (this) {
                case MIDNIGHT:
                    return ContextCompat.getColor(context, R.color.ui_preset_midnight_on_surface);
                case FOREST:
                    return ContextCompat.getColor(context, R.color.ui_preset_forest_on_surface);
                case SUNSET:
                    return ContextCompat.getColor(context, R.color.ui_preset_sunset_on_surface);
                case SYSTEM:
                default:
                    return resolveAttrColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer);
            }
        }
    }

    public static ColorPreset getColorPreset(Context context) {
        String value = prefs(context).getString(PREF_COLOR_PRESET, ColorPreset.SYSTEM.key);
        return ColorPreset.fromValue(value);
    }

    public static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    private static @ColorInt int resolveAttrColor(@NonNull Context context, int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
}
