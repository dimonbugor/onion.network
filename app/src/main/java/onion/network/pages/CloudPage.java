package onion.network.pages;

import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import onion.network.ui.MainActivity;
import onion.network.R;

public class CloudPage extends BasePage {

    String TAG = "CloudPage";

    LinearLayout contentView;
    View vmore, fmore;

    View wallScroll;

    public CloudPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.cloud_page, this, true);
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
        return "Cloud";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_cloud;
    }

    @Override
    public void load() {
    }

}
