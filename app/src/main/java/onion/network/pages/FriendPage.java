

package onion.network.pages;

import static onion.network.helpers.BitmapHelper.getCircledBitmap;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import onion.network.FriendTool;
import onion.network.Item;
import onion.network.models.ItemResult;
import onion.network.ItemTask;
import onion.network.ui.MainActivity;
import onion.network.R;

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
        return R.drawable.ic_people_white_36dp;
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
                    ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
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

                    Bitmap th = item.bitmap("thumb");
                    if (th != null) {
                        thumb.setImageBitmap(getCircledBitmap(th));
                    }

                    if (activity.address.isEmpty()) {
                        it.setOnLongClickListener(new OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {

                                AlertDialog dialogDeleteFriend = new AlertDialog.Builder(context, R.style.RoundedAlertDialog)
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
                                    dialogDeleteFriend.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                                    dialogDeleteFriend.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
                                });
                                dialogDeleteFriend.show();

                                return true;
                            }
                        });
                    }

                    contentView.addView(v);

                }

                if (i == 0 && itemResult.size() == 0 && !itemResult.loading()) {
                    View v = activity.getLayoutInflater().inflate(R.layout.friend_empty, contentView, false);
                    contentView.addView(v);
                }

                findViewById(R.id.offline).setVisibility(!itemResult.ok() && !itemResult.loading() ? View.VISIBLE : View.GONE);
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

    void loadMore() {
        if (smore == null) return;
        String smore2 = smore;
        smore = null;
        load(imore, smore2);
    }

}
