

package onion.network.ui;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import onion.network.models.FriendTool;
import onion.network.pages.BlogPage;
import onion.network.services.HostService;
import onion.network.models.Item;
import onion.network.models.ItemTask;
import onion.network.models.Notifier;
import onion.network.models.QR;
import onion.network.R;
import onion.network.models.Site;
import onion.network.TorManager;
import onion.network.services.UpdateScheduler;
import onion.network.models.WallBot;
import onion.network.databases.ChatDatabase;
import onion.network.databases.ItemDatabase;
import onion.network.databases.RequestDatabase;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;
import onion.network.pages.BasePage;
import onion.network.pages.ChatPage;
import onion.network.pages.CloudPage;
import onion.network.pages.ConversationPage;
import onion.network.pages.FriendPage;
import onion.network.pages.InfoPage;
import onion.network.pages.PrivacyPage;
import onion.network.pages.ProfilePage;
import onion.network.pages.RequestPage;
import onion.network.pages.WallPage;
import onion.network.ui.views.ArcButtonLayout;
import onion.network.ui.views.RequestTool;

public class MainActivity extends AppCompatActivity {

    private static MainActivity instance = null;
    public String address = "";
    public String name = "";
    public ItemResult nameItemResult = new ItemResult();
    public RequestPage requestPage;
    ItemDatabase db;
    WallPage wallPage;
    FriendPage friendPage;
    BasePage[] pages;
    int REQUEST_QR = 12;
    String TAG = "Activity";
    Timer timer = null;
    ChatPage chatPage;

    ArcButtonLayout arcButtonLayout;
    FloatingActionButton menuFab;
    private ViewPager viewPager;
    private View dimOverlay;
    public static int REQUEST_CAMERA = 24;
    public static int REQUEST_PICKER = 25;

    public static void addFriendItem(final Context context, String a, String name) {

        final String address = a.trim().toLowerCase();

        if (ItemDatabase.getInstance(context).hasKey("friend", address)) return;

        JSONObject o = new JSONObject();
        try {
            o.put("addr", address);
            if (name != null && !name.isEmpty()) o.put("name", name);
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
        ItemDatabase.getInstance(context).put(new Item("friend", address, "" + (100000000000000l - System.currentTimeMillis()), o));

    }

    synchronized public static MainActivity getInstance() {
        return instance;
    }

    public static void prefetch(Context context, String address) {
        new ItemTask(context, address, "name").execute2();
        prefetchExtra(context, address);
    }

    static void prefetchExtra(Context context, String address) {
        new ItemTask(context, address, "thumb").execute2();
    }

    public void blink(final int id) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < getChildrenViews(arcButtonLayout); i++) {
                        if (pages[i].getIcon() == id) {
                            View v = ((ViewGroup) arcButtonLayout.getChildAt(0)).getChildAt(i);
                            ObjectAnimator backgroundColorAnimator = ObjectAnimator.ofObject(v,
                                    "backgroundColor",
                                    new ArgbEvaluator(),
                                    0x88ffffff,
                                    0x00ffffff);
                            backgroundColorAnimator.setDuration(300);
                            backgroundColorAnimator.start();
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        });
    }

    public int getChildrenViews(ViewGroup parent) {
        int count = parent.getChildCount();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof ViewGroup) {
                count += getChildrenViews((ViewGroup) parent.getChildAt(i));
            }
        }
        return count;
    }

    @Override
    public Intent getSupportParentActivityIntent() {
        return getParentActivityIntentImpl();
    }

    @Override
    public Intent getParentActivityIntent() {
        return getParentActivityIntentImpl();
    }

    private Intent getParentActivityIntentImpl() {
        return new Intent(this, MainActivity.class);
    }

    public void log(String s) {
        Log.i(TAG, s);
    }

    void handleIntent(Intent intent) {

        if (intent != null) {
            Log.i(TAG, intent.toString());
            Uri uri = intent.getData();
            if (uri != null) {

                Log.i(TAG, uri.toString());

                {
                    try {
                        String ur = intent.getDataString();
                        log("ur " + ur);
                        String scheme = ur.substring(0, ur.indexOf(':'));
                        log("scheme " + scheme);
                        String host = ur.substring(ur.indexOf(':') + 1);
                        log("host1 " + host);
                        if (host.charAt(0) == '/') host = host.substring(1);
                        if (host.charAt(0) == '/') host = host.substring(1);
                        host = host.substring(0, 16);
                        log("host " + host);
                        if ("onionnet".equals(scheme) || "onionet".equals(scheme) || "onnet".equals(scheme)) {
                            address = host;
                            log("onionnet");
                            return;
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                Log.i(TAG, "host " + uri.getHost());

                if (uri.getHost() != null && uri.getHost().equals("network.onion")) {
                    List<String> pp = uri.getPathSegments();
                    address = pp.size() > 0 ? pp.get(0) : null;
                    name = pp.size() > 1 ? pp.get(1) : "";
                    Log.i(TAG, "ONION NETWORK URI " + address);
                    return;
                }

                if (uri.getHost() != null && uri.getHost().endsWith(".onion") || uri.getHost() != null && uri.getHost().contains(".onion.")) {
                    if (uri.getPath().equalsIgnoreCase("/network.onion") || uri.getPath().toLowerCase().startsWith("/network.onion/")) {
                        for (String s : uri.getHost().split("\\.")) {
                            if (s.length() == 16) {
                                address = s;
                                Log.i(TAG, "ONION NETWORK URI " + address);
                                return;
                            }
                        }
                    }
                }

            }
        }

    }

    boolean overlayVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.i(TAG, "onCreate");

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        address = getIntent().getStringExtra("address");
        handleIntent(getIntent());

        if (address == null) address = "";
        address = address.trim().toLowerCase();
        if (address.equals(TorManager.getInstance(this).getID())) address = "";

        db = ItemDatabase.getInstance(this);

        wallPage = new WallPage(this);
        friendPage = new FriendPage(this);

        if (address.isEmpty()) {
            requestPage = new RequestPage(this);
            pages = new BasePage[]{
                    wallPage,
                    friendPage,
                    requestPage,
                    new ConversationPage(this),
                    new ProfilePage(this),
                    new BlogPage(this),
                    new CloudPage(this),
                    new PrivacyPage(this),
            };
        } else {
            chatPage = new ChatPage(this);
            pages = new BasePage[]{
                    wallPage,
                    friendPage,
                    chatPage,
                    new ProfilePage(this),
                    new BlogPage(this),
                    new CloudPage(this),
                    new PrivacyPage(this),
            };
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Встановити білий tint для всіх іконок меню
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(Color.WHITE);
        toolbar.getOverflowIcon().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);

        if (!address.isEmpty()) {

            setTitle(address);

            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Set up the ViewPager with the sections adapter.
        viewPager = (ViewPager) findViewById(R.id.container);
        viewPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pages.length;
            }

            @Override
            public boolean isViewFromObject(View view, Object object) {
                return view == object;
            }

            @Override
            public Object instantiateItem(final ViewGroup container, int position) {
                View v = pages[position];
                container.addView(v);
                return v;
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }

        });

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                // Це викликається під час свайпу (між сторінками), якщо треба "в процесі"
            }

            @Override
            public void onPageSelected(int position) {
                // 🔹 Тут ти отримаєш swipe (перехід на сторінку)
                Log.d("ViewPager", "Page selected: " + position);

                fabvis();
                togglePostMainMenu();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                // 0 = idle, 1 = dragging, 2 = settling
                // Якщо треба відстежити момент початку свайпу — тут
                Log.d("ViewPager", "Scroll state changed: " + state);
            }
        });

        menuFab = findViewById(R.id.menuFab);
        dimOverlay = findViewById(R.id.dimOverlay);
        arcButtonLayout = findViewById(R.id.arcButtonLayout);
        arcButtonLayout.setFab(menuFab);
        arcButtonLayout.setOnExpansionChangedListener(expanded -> {
            fadeOverlay(dimOverlay, expanded);
        });

        for (int i = 0; i < pages.length; i++) {
            int icon = pages[i].getIcon();

            RelativeLayout relativeLayout =
                    (RelativeLayout) View.inflate(this, R.layout.tab_item, null);
            relativeLayout.setId(300 + i);
            FloatingActionButton fab = relativeLayout.findViewById(R.id.itemFab);
            fab.setId(100 + i);
            fab.setImageDrawable(ContextCompat.getDrawable(this, icon));
            fab.setContentDescription(pages[i].getTitle());
            int finalI = i;
            fab.setOnClickListener(v -> onTabSelected(finalI));

            if (relativeLayout.getParent() != null) {
                ((CoordinatorLayout) relativeLayout.getParent()).removeView(relativeLayout);
            }
            relativeLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            arcButtonLayout.addButton(relativeLayout);
        }

        initTabs();


        String pagestr = getIntent().getStringExtra("page");
        if (pagestr != null && !pagestr.isEmpty()) {
            for (int i = 0; i < pages.length; i++) {
                if (pages[i].getPageIDString().equals(pagestr)) {
                    viewPager.setCurrentItem(i);
                }
            }
        }


        if (Intent.ACTION_SEND.equals(getIntent().getAction()) && getIntent().getType() != null) {

            if ("text/plain".equals(getIntent().getType())) {
                String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && !text.isEmpty()) {
                    wallPage.writePost(text, null);
                }
            }

            if (getIntent().getType().startsWith("image/")) {
                Uri imageUri = (Uri) getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (imageUri != null) {
                    Bitmap image = null;
                    try {
                        image = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    }
                    if (image != null) {
                        wallPage.writePost(null, image);
                    }
                }
            }

        }


        new Thread() {
            @Override
            public void run() {
                try {
                    Site.getInstance(getApplicationContext());
                } catch (Exception t) {
                    t.printStackTrace();
                }
            }
        }.start();


        fabvis();

        WallBot.getInstance(this);

    }

    @Override protected void onStart() {
        super.onStart();
        Choreographer.getInstance().postFrameCallback(menuWatcher);
    }

    @Override protected void onStop() {
        super.onStop();
        Choreographer.getInstance().removeFrameCallback(menuWatcher);
        if (overlayVisible) {
            overlayVisible = false;
            fadeOverlay(dimOverlay, false);
        }
    }

    /** Детектим, чи є зараз popup overflow меню серед глобальних в'юшок WindowManager */
    @SuppressWarnings("unchecked")
    private boolean isOverflowMenuShowing() {
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmgClass.getMethod("getInstance");
            Object wmg = getInstance.invoke(null);

            Field mViewsField = wmgClass.getDeclaredField("mViews");
            mViewsField.setAccessible(true);
            List<View> views = (List<View>) mViewsField.get(wmg);
            if (views == null) return false;

            for (View v : views) {
                String name = v.getClass().getName();
                // різні варіанти на різних версіях Android/AppCompat
                if ((name.contains("MenuPopupWindow")
                        || name.contains("PopupDecorView")
                        || name.contains("MenuDropDownListView")
                        || name.contains("ListPopupWindow$DropDownListView"))
                        && v.isShown()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private final Choreographer.FrameCallback menuWatcher = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            boolean open = isOverflowMenuShowing();
            if (open != overlayVisible) {
                overlayVisible = open;
                fadeOverlay(dimOverlay, open);
            }
            // безперервно спостерігаємо
            Choreographer.getInstance().postFrameCallback(menuWatcher);
        }
    };

    private void fadeOverlay(View overlay, boolean show) {
        overlay.animate()
                .alpha(show ? 1f : 0f)
                .setDuration(300)
                .withStartAction(() -> {
                    if (show) {
                        overlay.setVisibility(View.VISIBLE);
                        overlay.setOnClickListener(v -> arcButtonLayout.toggleMenu());
                    }
                })
                .withEndAction(() -> {
                    if (!show) {
                        overlay.setVisibility(View.GONE);
                        overlay.setOnClickListener(null);
                    }
                })
                .start();
    }

    public void startHostService() {
        startService(new Intent(this, HostService.class));
    }

    void showEnterId() {
        View dialogView = getLayoutInflater().inflate(R.layout.friend_dialog, null);
        final EditText addressEdit = (EditText) dialogView.findViewById(R.id.address);
        AlertDialog dialog = new AlertDialog.Builder(MainActivity.this, R.style.RoundedAlertDialog)
                .setTitle("Enter ID")
                .setView(dialogView)
                .setNegativeButton("Cancel", (d, which) -> {
                })
                .setPositiveButton("Open", (d, which) -> {
                    final String address = addressEdit.getText().toString().trim().toLowerCase();
                    if (address.length() > 56) {
                        snack("Invalid ID");
                        return;
                    }
                    startActivity(new Intent(MainActivity.this, MainActivity.class).putExtra("address", address));

                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    public void initTabs() {

        if (pages == null) {
            return;
        }

        for (int i = 0; i < pages.length; i++) {
            BasePage page = pages[i];
            RelativeLayout relativeLayout = arcButtonLayout.findViewById(300 + i);
            TextView badge = (TextView) relativeLayout.findViewById(R.id.badge);
            String t = page.getBadge();
            if (t == null) t = "";
            badge.setVisibility("".equals(t) ? View.GONE : View.VISIBLE);
            badge.setText(t);
        }

    }

    void onTabSelected(int tabPosition) {
        viewPager.setCurrentItem(tabPosition, true);
        fabvis();
        pages[tabPosition].onTabSelected();
        arcButtonLayout.toggleMenu();
    }

    public void showAddFriend() {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                .setTitle("Add Friend")
                .setItems(new String[]{
                        "Scan QR",
                        "Show QR",
                        "Enter ID",
                        "Show ID",
                        "Address",
                        "Invite friends",
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) scanQR();
                        if (which == 1) showQR();

                        if (which == 2) showEnterId();
                        if (which == 3) showId();

                        if (which == 4) showUrl();

                        if (which == 5) inviteFriend();
                    }
                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    void scanQR() {

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(takePictureIntent, REQUEST_QR);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (BasePage page : pages) {
            page.onActivityResult(requestCode, resultCode, data);
        }

        if (resultCode != RESULT_OK) {
            return;
        }

        if (requestCode == REQUEST_QR) {
            Bitmap bitmap = (Bitmap) data.getExtras().get("data");

            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap bBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();

            try {
                Result result = reader.decode(bBitmap);
                String str = result.getText();
                Log.i("ID", str);

                String[] tokens = str.split(" ", 3);

                if (tokens.length < 2 || !tokens[0].equals("network.onion")) {
                    snack("QR Code Invalid, incompatible ID");
                    return;
                }

                String id = tokens[1].toLowerCase();

                if (id.length() != 16) {
                    snack("QR Code Invalid");
                    return;
                }

                String name = "";
                if (tokens.length > 2) {
                    name = tokens[2];
                }

                contactDialog(id, name);

                return;

            } catch (Exception ex) {
                snack("QR Code Invalid");
                ex.printStackTrace();
            }
        }

    }

    public String getAppName() {
        return Utils.getAppName(this);
    }

    void contactDialog(String id, String name) {

        boolean isFriend = db.hasKey("friend", id);

        if (name.isEmpty()) {
            name = "Anonymous";
        }

        if (isFriend) {
            name += "  (friend)";
        }

        AlertDialog.Builder a = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                .setTitle(name)
                .setMessage(id);

        prefetch(this, id);

        final String n = name;
        final String i = id;

        if (!isFriend) {
            a.setPositiveButton("Add Friend", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    addFriend(i, n);
                    viewPager.setCurrentItem(1, true);
                    contactDialog(i, n);
                }
            });
        }

        a.setNeutralButton("Close", null);

        a.setNegativeButton("View Profile", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent(MainActivity.this, MainActivity.class).putExtra("address", i));
            }
        });

        a.show();
    }

    public String getID() {
        if (address != null && !address.isEmpty()) return address.trim().toLowerCase();
        return TorManager.getInstance(this).getID();
    }

    String getName() {
        return address.isEmpty() ? ItemDatabase.getInstance(this).getstr("name") : name;
    }

    void showQR() {
        String n = getName();

        String txt = "network.onion " + getID() + " " + n;
        txt = txt.trim();

        Bitmap bitmap = QR.make(txt);

        ImageView view = new ImageView(this);
        view.setImageBitmap(bitmap);

        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        view.setPadding(pad, pad, pad, pad);

        Rect displayRectangle = new Rect();
        Window window = getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(displayRectangle);
        int s = (int) (Math.min(displayRectangle.width(), displayRectangle.height()) * 0.9);
        view.setMinimumWidth(s);
        view.setMinimumHeight(s);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                .setView(view)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    public void addFriend(final String address, String name) {

        if (db.hasKey("friend", address)) return;

        addFriendItem(this, address, name);

        RequestDatabase.getInstance(this).addOutgoing(address);
        new Thread() {
            @Override
            public void run() {
                RequestTool.getInstance(MainActivity.this).sendRequest(address);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }
        }.start();

        prefetch(this, address);
        load();

        UpdateScheduler.getInstance(this).put(address);
    }

    public void load() {

        updateMenu();

        initTabs();

        {

            final String a = address;

            new ItemTask(this, a, "name", "", 1) {
                @Override
                protected void onProgressUpdate(ItemResult... values) {
                    Item item = values[0].one();
                    nameItemResult = values[0];
                    for (BasePage page : pages) {
                        page.onNameItem(item);
                    }
                    if (!address.isEmpty()) {
                        if (item.json().has("name")) {
                            name = item.json().optString("name");
                        }
                        if (db.hasKey("friend", a)) {
                            Item it = db.getByKey("friend", address);
                            if (it != null) {
                                JSONObject o = it.json();
                                try {
                                    o.remove("name");
                                    if (name != null && !name.isEmpty()) o.put("name", name);
                                } catch (JSONException ex) {
                                    throw new RuntimeException(ex);
                                }
                                db.put(new Item(it.type(), it.key(), it.index(), o));
                            }
                        }
                    }
                    updateActionBar();
                }
            }.execute2();

            prefetchExtra(this, a);

        }

        for (BasePage page : pages) {
            page.load();
        }

        fabvis();

        updateActionBar();

    }

    void updateActionBar() {
        if (address.isEmpty()) {
            getSupportActionBar().setTitle(getAppName());
            getSupportActionBar().setSubtitle(null);
        } else {
            if (name.isEmpty()) {
                getSupportActionBar().setTitle(address);
                getSupportActionBar().setSubtitle(null);
            } else {
                getSupportActionBar().setTitle(name);
                getSupportActionBar().setSubtitle(address);
            }
        }
    }

    public BasePage currentPage() {
        if (viewPager == null) return null;
        Object o = viewPager.getCurrentItem();
        //Log.i(TAG, "" + o + " " + o.getClass().toString());
        return pages[(Integer) o];
    }

    void fabvis() {
        initTabs();
        int cFab = currentPage().getFab();
        for (final BasePage page : pages) {
            FloatingActionButton fab = (FloatingActionButton) findViewById(page.getFab());
            if (fab != null) {
                if (fab.getId() == cFab && "".equals(address)) {
                    if (!fab.isShown()) {
                        fab.show();
                    }
                    fab.setOnClickListener(v -> page.onFab());
                } else {
                    if (fab.isShown()) {
                        fab.hide();
                        fab.setOnClickListener(null);
                    }
                }
            } else {
                if (page instanceof ChatPage) {
                    fab = (FloatingActionButton) findViewById(R.id.wallFab);
                    fab.hide();
                    fab = null;
                }
            }
        }
    }

    boolean actionPhotoOption = false;
    boolean actionCameraOption = false;
    boolean actionBlogTitleOption = false;
    boolean actionShareOption = false;
    boolean actionHomeOption = false;
    boolean actionAddPostOption = false;
    boolean actionStyleOption = false;

    boolean actionRefreshQrOption = true;
    boolean actionMenuScanQrOption = true;
    boolean actionMenuShowMyQrOption = true;
    boolean actionMenuEnterIdOption = true;
    boolean actionMenuShowMyIdOption = true;
    boolean actionMenuShowUriOption = true;
    boolean actionMenuInviteFriendsOption = true;

    public void togglePostMainMenu() {
        boolean showBlogScreen = currentPage() instanceof BlogPage;
        if (showBlogScreen) {
            actionPhotoOption = true;
            actionCameraOption = true;
            actionBlogTitleOption = true;
            actionShareOption = true;
            actionHomeOption = true;
            actionAddPostOption = true;
            actionStyleOption = true;

            actionRefreshQrOption = false;
            actionMenuScanQrOption = false;
            actionMenuShowMyQrOption = false;
            actionMenuEnterIdOption = false;
            actionMenuShowMyIdOption = false;
            actionMenuShowUriOption = false;
            actionMenuInviteFriendsOption = false;
        } else {
            actionPhotoOption = false;
            actionCameraOption = false;
            actionBlogTitleOption = false;
            actionShareOption = false;
            actionHomeOption = false;
            actionAddPostOption = false;
            actionStyleOption = false;

            actionRefreshQrOption = true;
            actionMenuScanQrOption = true;
            actionMenuShowMyQrOption = true;
            actionMenuEnterIdOption = true;
            actionMenuShowMyIdOption = true;
            actionMenuShowUriOption = true;
            actionMenuInviteFriendsOption = true;
        }
        updateMenu();
    }

    void updateMenu() {
        invalidateOptionsMenu();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_photo).setVisible(actionPhotoOption);
        menu.findItem(R.id.action_camera).setVisible(actionCameraOption);
        menu.findItem(R.id.action_blog_title).setVisible(actionBlogTitleOption);
        menu.findItem(R.id.action_share).setVisible(actionShareOption);
        menu.findItem(R.id.action_home).setVisible(actionHomeOption);
        menu.findItem(R.id.action_add_post).setVisible(actionAddPostOption);
        menu.findItem(R.id.action_style).setVisible(actionStyleOption);

        menu.findItem(R.id.action_refresh).setVisible(actionMenuScanQrOption);
        menu.findItem(R.id.action_menu_scan_qr).setVisible(actionMenuScanQrOption);
        menu.findItem(R.id.action_menu_show_my_qr).setVisible(actionMenuShowMyQrOption);
        menu.findItem(R.id.action_menu_enter_id).setVisible(actionMenuEnterIdOption);
        menu.findItem(R.id.action_menu_show_my_id).setVisible(actionMenuShowMyIdOption);
        menu.findItem(R.id.action_menu_show_uri).setVisible(actionMenuShowUriOption);
        menu.findItem(R.id.action_menu_invite_friends).setVisible(actionMenuInviteFriendsOption);

        menu.findItem(R.id.action_add_friend).setVisible(!address.isEmpty() && !db.hasKey("friend", address));
        menu.findItem(R.id.action_friends).setVisible(!address.isEmpty() && db.hasKey("friend", address));
        menu.findItem(R.id.action_clear_chat).setVisible(!address.isEmpty());
        menu.findItem(R.id.action_menu_invite_friends).setVisible(address.isEmpty());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_blog_title) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).editTitle();
            }
            return true;
        }
        if (id == R.id.action_photo) {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "Complete action using"), REQUEST_PICKER);
            return true;
        }
        if (id == R.id.action_camera) {
            //getPackageManager().hasSystemFeature(CAME)
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            //takePictureIntent.resolveActivity(getPackageManager());
            startActivityForResult(takePictureIntent, REQUEST_CAMERA);
            return true;
        }
        if (id == R.id.action_add_post) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).addPost();
            }
            return true;
        }
        if (id == R.id.action_share) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).share();
            }
            return true;
        }
        if (id == R.id.share) {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=" + getPackageName());
            intent.setType("text/plain");
            startActivity(intent);
        }
        if (id == R.id.about) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).showAbout();
            }
            return true;
        }
        if (id == R.id.action_style) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).selectStyle();
            }
            return true;
        }
        if (id == R.id.action_home) {
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                ((BlogPage) page).goHome();
            }
            return true;
        }

        if (id == R.id.action_friends) {
            AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                    .setTitle("Friends")
                    .setMessage("You are friends with this user")
                    .setNeutralButton("Remove friend", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FriendTool.getInstance(MainActivity.this).unfriend(address);
                            load();
                            snack("Contact removed.");
                        }
                    })
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            });
            dialog.show();
            return true;
        }
        if (id == R.id.action_add_friend) {
            addFriend(address, name);
            snack("Added friend");
            return true;
        }
        if (id == R.id.action_refresh) {
            load();
            return true;
        }
        if (id == R.id.action_menu_enter_id) {
            showEnterId();
            return true;
        }
        if (id == R.id.action_menu_show_my_id) {
            showId();
            return true;
        }
        if (id == R.id.action_menu_show_uri) {
            showUrl();
        }
        if (id == R.id.action_menu_scan_qr) {
            scanQR();
            return true;
        }
        if (id == R.id.action_menu_show_my_qr) {
            showQR();
            return true;
        }
        if (id == R.id.action_menu_invite_friends) {
            inviteFriend();
            return true;
        }
        if (id == R.id.action_menu_rate) {
            rateThisApp();
            return true;
        }
        if (id == R.id.action_menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_clear_chat) {
            AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                    .setTitle("Clear chat")
                    .setMessage("Do you really want to delete all messages exchanged with this contact?")
                    .setNegativeButton("No", null)
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ChatDatabase.getInstance(MainActivity.this).clearChat(address);
                            toast("Chat cleared");
                            chatPage.load();
                        }
                    })
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            });
            dialog.show();
        }

        return super.onOptionsItemSelected(item);
    }

    void rateThisApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName()));
            PackageManager pm = getPackageManager();
            for (ApplicationInfo packageInfo : pm.getInstalledApplications(0)) {
                if (packageInfo.packageName.equals("com.android.vending"))
                    intent.setPackage("com.android.vending");
            }
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        }
    }

    public void showId() {
        final String id = getID();
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                .setTitle("ID: " + id)
                .setNegativeButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setText(id);
                        snack("ID copied to clipboard.");
                    }
                })
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, id).setType("text/plain"));
                    }
                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    void showUrl() {

        final View v = getLayoutInflater().inflate(R.layout.url_dialog, null);

        {
            final String onionLink = String.format("%s.onion/network.onion", getID());
            ((TextView) v.findViewById(R.id.onion_link_text)).setText(onionLink);
            v.findViewById(R.id.onion_link_copy).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setText(onionLink);
                    toast("Link copied to clipboard.");
                }
            });
            v.findViewById(R.id.onion_link_share).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "http://" + onionLink).setType("text/plain"));
                }
            });
            v.findViewById(R.id.onion_link_view).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + onionLink)));
                }
            });
        }

        {
            final String clearnetLink = String.format("%s.onion.to/network.onion", getID());
            ((TextView) v.findViewById(R.id.clearnet_link_text)).setText(clearnetLink);
            v.findViewById(R.id.clearnet_link_copy).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setText(clearnetLink);
                    toast("Link copied to clipboard.");
                }
            });
            v.findViewById(R.id.clearnet_link_share).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "http://" + clearnetLink).setType("text/plain"));
                }
            });
            v.findViewById(R.id.clearnet_link_view).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + clearnetLink)));
                }
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.RoundedAlertDialog)
                .setView(v)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();

    }

    public void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    public void publishPost(JSONObject o) {
        long k = System.currentTimeMillis();
        long i = 100000000000000l - k;
        db.put(new Item("post", "" + k, "" + i, o));
    }

    public void sharePost(JSONObject o) {
        try {
            o.put("date", null);
            String k = "" + o.toString().hashCode();
            o.put("date", "" + System.currentTimeMillis());
            long i = 100000000000000l - System.currentTimeMillis();
            db.put(new Item("post", k, "" + i, o));
            snack("Post republished");
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }

    void snack(String str) {
        Snackbar.make(viewPager, str, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (BasePage page : pages) {
            page.onResume();
        }
        instance = this;
        load();

        Notifier.getInstance(this).clr();

        {
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                public void run() {
                    Log.i(TAG, "send unsent requests");
                    RequestTool.getInstance(MainActivity.this).sendUnsentReq(address);
                }
            }, 0, 1000 * 60 * 5);
        }
    }

    @Override
    protected void onPause() {
        try {

            super.onPause();

            {
                timer.cancel();
                timer.purge();
                timer = null;
            }

            for (BasePage page : pages) {
                page.onPause();
            }

        } finally {
            if (instance == this) {
                instance = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TorManager.getInstance(this).stopTor();
    }

    public void lightbox(Bitmap bitmap) {
        final ImageView v = (ImageView) findViewById(R.id.lightbox);
        v.setImageBitmap(bitmap);
        v.setVisibility(View.VISIBLE);
        v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.lightbox_show));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View vv) {
                lightboxHide();
            }
        });
    }

    private void lightboxHide() {
        final ImageView v = (ImageView) findViewById(R.id.lightbox);
        Animation animation = AnimationUtils.loadAnimation(MainActivity.this, R.anim.lightbox_hide);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                v.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        v.startAnimation(animation);
    }

    @Override
    public void onBackPressed() {
        final ImageView v = (ImageView) findViewById(R.id.lightbox);
        if (v.getVisibility() == View.VISIBLE) {
            lightboxHide();
            return;
        }
        super.onBackPressed();
    }

    void inviteFriend() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);

        intent.putExtra(Intent.EXTRA_TEXT, String.format(getString(R.string.invitation_text), TorManager.getInstance(this).getID(), Uri.encode(getName()), getAppName()));
        intent.setType("text/plain");

        startActivity(intent);
    }

}
