package onion.network.helpers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;

public final class DialogHelper {

    private DialogHelper() {
    }

    public static AlertDialog.Builder themedBuilder(Context context) {
        return new AlertDialog.Builder(context, ThemeManager.getDialogThemeResId(context));
    }

    public static AlertDialog.Builder styledBuilder(Context context, @StyleRes int styleResId) {
        return new AlertDialog.Builder(context, styleResId);
    }

    public static void show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        show(dialog);
    }

    public static void show(AlertDialog dialog) {
        show(dialog, null);
    }

    public static void show(AlertDialog dialog, @Nullable DialogInterface.OnShowListener extraListener) {
        dialog.setOnShowListener(d -> {
            if (extraListener != null) {
                extraListener.onShow(dialog);
            }
            tintButtons(dialog);
        });
        dialog.show();
    }

    public static void showConfirm(
            Context context,
            @StringRes int titleResId,
            @StringRes int messageResId,
            @StringRes int positiveResId,
            @Nullable Runnable positiveAction,
            @StringRes int negativeResId,
            @Nullable Runnable negativeAction
    ) {
        AlertDialog.Builder builder = themedBuilder(context)
                .setTitle(titleResId)
                .setMessage(messageResId)
                .setPositiveButton(positiveResId, (dialog, which) -> {
                    if (positiveAction != null) {
                        positiveAction.run();
                    }
                })
                .setNegativeButton(negativeResId, (dialog, which) -> {
                    if (negativeAction != null) {
                        negativeAction.run();
                    }
                });
        AlertDialog dialog = builder.create();
        show(dialog);
    }

    private static void tintButtons(AlertDialog dialog) {
        int color = ThemeManager.getColor(dialog.getContext(), android.R.attr.actionMenuTextColor);
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color);
        }
    }
}
