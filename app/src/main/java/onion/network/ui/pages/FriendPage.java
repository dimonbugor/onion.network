

package onion.network.ui.pages;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;

import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.models.FriendTool;
import onion.network.models.Item;
import onion.network.models.ItemResult;
import onion.network.models.ItemTask;
import onion.network.ui.MainActivity;
import onion.network.R;
import onion.network.ui.views.AvatarView;

public class FriendPage extends BasePage {

    String TAG = "FriendPage";

    LinearLayout contentView;
    int count = 8;

    String smore;
    int imore;
    View vmore, fmore;

    View wallScroll;

    public FriendPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.friend_page, this, true);
        contentView = (LinearLayout) findViewById(R.id.contentView);

        wallScroll = findViewById(R.id.wallScroll);
        wallScroll.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                //if(vmore != null && vmore.getRe
                Log.i(TAG, "onTouch wallScroll");
                if (event.getAction() == MotionEvent.ACTION_DOWN && vmore != null) {
                    int[] p = new int[]{0, 0};
                    wallScroll.getLocationOnScreen(p);
                    p[0] += (int) event.getX();
                    p[1] += (int) event.getY();
                    Rect rect = new Rect();
                    vmore.getHitRect(rect);
                    if (vmore.getGlobalVisibleRect(rect)) {
                        Log.i(TAG, "onTouch: " + p[0] + " " + p[1]);
                        Log.i(TAG, "onTouch: " + rect.left + " " + rect.top + " " + rect.right + " " + rect.bottom);
                        if (rect.contains(p[0], p[1])) {
                            Log.i(TAG, "onTouch: load more");
                            loadMore();
                            //contentView.requestDisallowInterceptTouchEvent(true);
                        } else {
                            Log.i(TAG, "onTouch: miss");
                        }
                    }
                }
                return false;
            }
        });
        vmore = findViewById(R.id.wallLoadMore);
        fmore = findViewById(R.id.wallLoadMoreFrame);
    }

    @Override
    public String getTitle() {
        return "Friends";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_people;
    }

    @Override
    public void load() {
        load(0, "");
    }

    @Override
    public int getFab() {
        return R.id.friendFab;
    }

    @Override
    public void onFab() {
        activity.showAddFriend();
    }

    void load(final int i, String s) {
        new ItemTask(getContext(), address, "friend", s, count) {

            void fill(ItemResult itemResult, boolean finished) {

                if (contentView.getChildCount() > i) {
                    contentView.removeViews(i, contentView.getChildCount() - i);
                }

                for (int i = 0; i < itemResult.size(); i++) {

                    final Item item = itemResult.at(i);

                    JSONObject o = item.json(context, address);

                    View v = activity.getLayoutInflater().inflate(R.layout.friend_item, contentView, false);

                    TextView address = ((TextView) v.findViewById(R.id.address));
                    TextView name = ((TextView) v.findViewById(R.id.name));
                    AvatarView thumb = (AvatarView) v.findViewById(R.id.thumb);
                    View it = v.findViewById(R.id.item);

                    final String addr = o.optString("addr");

                    String n = o.optString("name");

                    if (n == null || n.isEmpty()) {
                        n = "Anonymous";
                    }

                    name.setText(n);
                    address.setText(addr);

                    it.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            getContext().startActivity(new Intent(getContext(), MainActivity.class).putExtra("address", addr));
                        }
                    });

                    Bitmap photoThumb = item.bitmap("thumb");
                    Bitmap videoThumb = item.bitmap("video_thumb");
                    String videoUri = o.optString("video", "").trim();
                    thumb.bind(photoThumb, videoThumb, videoUri.isEmpty() ? null : videoUri);

                    if (activity.address.isEmpty()) {
                        it.setOnLongClickListener(new OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {

                                AlertDialog dialogDeleteFriend = new AlertDialog.Builder(context, ThemeManager.getDialogThemeResId(context))
                                        .setTitle("Delete Friend?")
                                        .setMessage("Do you really want to delete this friend?")
                                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                FriendTool.getInstance(context).unfriend(item.key());
                                                load();
                                            }
                                        })
                                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                            }
                                        })
                                        .create();
                                dialogDeleteFriend.setOnShowListener(d -> {
                                    dialogDeleteFriend.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                    dialogDeleteFriend.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                });
                                dialogDeleteFriend.show();

                                return true;
                            }
                        });
                    }

                    contentView.addView(v);
                    applyFriendItemStyle(v);

                }

                if (i == 0 && itemResult.size() == 0 && !itemResult.loading()) {
                    View v = activity.getLayoutInflater().inflate(R.layout.friend_empty, contentView, false);
                    contentView.addView(v);
                }

//                findViewById(R.id.offline).setVisibility(!itemResult.ok() && !itemResult.loading() ? View.VISIBLE : View.GONE);
                findViewById(R.id.loading).setVisibility(itemResult.loading() ? View.VISIBLE : View.GONE);

                smore = finished ? itemResult.more() : null;
                imore = i + count;

                {
                    if (smore != null) {
                        vmore.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Log.i(TAG, "onClick");
                                loadMore();
                            }
                        });
                        vmore.setOnTouchListener(new OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                                    Log.i(TAG, "onTouch down");
                                    loadMore();
                                }
                                return false;
                            }
                        });
                        fmore.setVisibility(View.VISIBLE);
                    } else {
                        vmore.setOnClickListener(null);
                        vmore.setOnTouchListener(null);
                        fmore.setVisibility(View.INVISIBLE);
                    }

                }

            }

            @Override
            protected void onProgressUpdate(ItemResult... x) {
                fill(x[0], false);
            }

            @Override
            protected void onPostExecute(ItemResult itemResult) {
                fill(itemResult, true);
            }

        }.execute2();
    }

    public void refreshAppearance() {
        if (contentView == null) return;
        for (int i = 0; i < contentView.getChildCount(); i++) {
            View child = contentView.getChildAt(i);
            if (child instanceof MaterialCardView) {
                applyFriendItemStyle(child);
            }
        }
    }

    private void applyFriendItemStyle(View view) {
        if (!(view instanceof MaterialCardView)) return;
        UiCustomizationManager.FriendCardConfig config = UiCustomizationManager.getFriendCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());

        MaterialCardView card = (MaterialCardView) view;
        card.setRadius(config.cornerRadiusPx);
        card.setContentPadding(config.horizontalPaddingPx, config.verticalPaddingPx,
                config.horizontalPaddingPx, config.verticalPaddingPx);
        card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
        card.setStrokeColor(preset.getAccentColor(getContext()));

        if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
            card.setCardBackgroundColor(ThemeManager.getColor(activity, com.google.android.material.R.attr.colorPrimaryContainer));
        } else {
            card.setCardBackgroundColor(preset.getSurfaceColor(getContext()));
        }

        ImageView avatar = card.findViewById(R.id.thumb);
        if (avatar != null) {
            View avatarContainer = (View) avatar.getParent();
            if (avatarContainer != null) {
                ViewGroup.LayoutParams params = avatarContainer.getLayoutParams();
                if (params != null) {
                    params.width = config.avatarSizePx;
                    params.height = config.avatarSizePx;
                    avatarContainer.setLayoutParams(params);
                }
            }
        }

        TextView name = card.findViewById(R.id.name);
        TextView address = card.findViewById(R.id.address);
        if (name != null) {
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.nameTextSizeSp);
        }
        if (address != null) {
            address.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.addressTextSizeSp);
        }

        if (name != null) {
            if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
                name.setTextColor(ThemeManager.getColor(activity, com.google.android.material.R.attr.colorOnBackground));
            } else {
                name.setTextColor(preset.getOnSurfaceColor(getContext()));
            }
        }
        if (address != null) {
            if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
                address.setTextColor(ThemeManager.getColor(activity, R.attr.white_80));
            } else {
                int onSurface = preset.getOnSurfaceColor(getContext());
                address.setTextColor(ColorUtils.setAlphaComponent(onSurface, 160));
            }
        }
    }

    void loadMore() {
        if (smore == null) return;
        String smore2 = smore;
        smore = null;
        load(imore, smore2);
    }

}
