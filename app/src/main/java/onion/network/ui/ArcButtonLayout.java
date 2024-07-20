package onion.network.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.design.widget.FloatingActionButton;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.List;

import onion.network.R;

public class ArcButtonLayout extends ViewGroup {
    private List<RelativeLayout> buttons;
    private boolean isExpanded = false;
    private FloatingActionButton fab;
    private int fabPosition;
    private int fabMarginLeft;
    private int fabMarginTop;
    private int fabMarginRight;
    private int fabMarginBottom;
    private int centerMarginLeft;
    private int centerMarginTop;
    private int centerMarginRight;
    private int centerMarginBottom;

    public ArcButtonLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        buttons = new ArrayList<>();

        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.ArcButtonLayout,
                0, 0);

        try {
            fabPosition = a.getInt(R.styleable.ArcButtonLayout_fabPosition, 1); // Default to bottom_center
            fabMarginLeft = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_fabMarginLeft, 0);
            fabMarginTop = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_fabMarginTop, 0);
            fabMarginRight = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_fabMarginRight, 0);
            fabMarginBottom = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_fabMarginBottom, 0);

            centerMarginLeft = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_centerMarginLeft, 0);
            centerMarginTop = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_centerMarginTop, 0);
            centerMarginRight = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_centerMarginRight, 0);
            centerMarginBottom = a.getDimensionPixelSize(R.styleable.ArcButtonLayout_centerMarginBottom, 0);
        } finally {
            a.recycle();
        }
    }

    public void setFab(FloatingActionButton fab) {
        this.fab = fab;
        fab.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenu();
            }
        });
    }

    public void addButton(RelativeLayout button) {
        buttons.add(button);
        addView(button);
        button.setVisibility(GONE);
    }

    public void toggleMenu() {
        isExpanded = !isExpanded;
        animateButtons();
    }

    private void animateButtons() {
        if (fab == null) return;

        int width = getWidth();
        int height = getHeight();
        //int radius = Math.min(width, height) / 2; // Set radius to half the width/height of the layout
        int radius = Math.min(width, height) - fab.getWidth() * 2; // Set radius to half the width/height of the layout

        //int cx = width - fab.getWidth() / 2 + centerMarginLeft - centerMarginRight; // Center of the layout
        int cx = width - fab.getWidth() * 2 + buttons.get(0).getWidth() / 2 - centerMarginRight + centerMarginLeft;
        //int cy = height / 2 + centerMarginTop - centerMarginBottom; // Center of the layout
        int cy = height - radius - fab.getHeight() - centerMarginBottom + centerMarginTop;

        float startAngle = 180; // Start from 9 o'clock
        float endAngle = 93; // End at 6 o'clock
        float angleStep = (endAngle - startAngle) / (buttons.size() - 1);

        int startX = fab.getLeft() + fab.getWidth() / 2; // Центр кнопки fab
        int startY = fab.getTop() + fab.getHeight() / 2; // Центр кнопки fab

        for (int i = 0; i < buttons.size(); i++) {
            RelativeLayout button = buttons.get(i);
            float angle = startAngle + angleStep * i;
            float radian = angle * (float) Math.PI / 180f;
            int x = (int) (cx + radius * Math.cos(radian));
            int y = (int) (cy + radius * Math.sin(radian));

            if (isExpanded) {
                button.setVisibility(VISIBLE);
                button.setAlpha(0f);
                button.setTranslationX(startX - button.getMeasuredWidth() / 2);
                button.setTranslationY(startY - button.getMeasuredHeight() / 2);
                button.animate()
                        .translationX(x - button.getMeasuredWidth() / 2)
                        .translationY(y - button.getMeasuredHeight() / 2)
                        .alpha(1f)
                        .setStartDelay(i * 100)
                        .setDuration(300)
                        .withStartAction(() -> button.setLayerType(View.LAYER_TYPE_HARDWARE, null))
                        .withEndAction(() -> button.setLayerType(View.LAYER_TYPE_NONE, null))
                        .start();
            } else {
                button.animate()
                        .translationX(startX - button.getMeasuredWidth() / 2)
                        .translationY(startY - button.getMeasuredHeight() / 2)
                        .alpha(0f)
                        .setStartDelay(i * 100)
                        .setDuration(300)
                        .withEndAction(() -> {
                            button.setVisibility(GONE);
                            button.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (RelativeLayout button : buttons) {
            button.layout(0, 0, button.getMeasuredWidth(), button.getMeasuredHeight());
        }

        if (fab != null) {
            int width = getWidth();
            int height = getHeight();
            int fabWidth = fab.getMeasuredWidth();
            int fabHeight = fab.getMeasuredHeight();
            int left, top;

            switch (fabPosition) {
                case 0: // bottom_left
                    left = fabMarginLeft;
                    top = height - fabHeight - fabMarginBottom;
                    break;
                case 2: // bottom_right
                    left = width - fabWidth - fabMarginRight;
                    top = height - fabHeight - fabMarginBottom;
                    break;
                case 1: // bottom_center
                default:
                    left = (width - fabWidth) / 2;
                    top = height - fabHeight - fabMarginBottom;
                    break;
            }

            fab.layout(left, top, left + fabWidth, top + fabHeight);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        for (RelativeLayout button : buttons) {
            measureChild(button, widthMeasureSpec, heightMeasureSpec);
        }

        if (fab != null) {
            measureChild(fab, widthMeasureSpec, heightMeasureSpec);
        }
    }
}