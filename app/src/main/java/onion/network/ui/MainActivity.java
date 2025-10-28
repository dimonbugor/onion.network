

package onion.network.ui;

import static onion.network.helpers.Const.REQUEST_CHOOSE_MEDIA;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_PROFILE;
import static onion.network.helpers.Const.REQUEST_TAKE_VIDEO;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import onion.network.helpers.DeepLinkParser;
import onion.network.models.FriendTool;
import onion.network.ui.controllers.LightboxController;
import onion.network.ui.menu.MenuState;
import onion.network.ui.pages.BlogPage;
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
import onion.network.helpers.DialogHelper;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;
import onion.network.ui.pages.BasePage;
import onion.network.ui.pages.ChatPage;
import onion.network.ui.pages.CloudPage;
import onion.network.ui.pages.ConversationPage;
import onion.network.ui.pages.FriendPage;
import onion.network.ui.pages.PrivacyPage;
import onion.network.ui.pages.ProfilePage;
import onion.network.ui.pages.RequestPage;
import onion.network.ui.pages.WallPage;
import onion.network.ui.views.ArcButtonLayout;
import onion.network.ui.views.AvatarView;
import onion.network.ui.views.RequestTool;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.settings.Settings;
import android.content.ClipData;
import android.content.ClipboardManager;

public class MainActivity extends AppCompatActivity {

    private static MainActivity instance = null;
    public String address = "";
    public String name = "";
    public ItemResult nameItemResult = new ItemResult();
    public RequestPage requestPage;
    ItemDatabase db;
    WallPage wallPage;
    FriendPage friendPage;
    ConversationPage conversationPage;
    BasePage[] pages;
    String TAG = "Activity";
    Timer timer = null;
    ChatPage chatPage;
    private String appliedTheme;

    ArcButtonLayout arcButtonLayout;
    FloatingActionButton menuFab;
    private ViewPager viewPager;
    private View dimOverlay;
    private ImageView lightboxImageView;
    private PlayerView lightboxVideoView;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    private LightboxController lightbox;
    private MenuState menuState = MenuState.normal();
    private ActivityResultLauncher<Intent> qrLauncher;
    private ActivityResultLauncher<Intent> mediaPickerLauncher;
    private ActivityResultLauncher<Intent> cameraForBlogLauncher;

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
        new ItemTask(context, address, "video").execute2();
        new ItemTask(context, address, "video_thumb").execute2();
    }

    public void blink(final int id) {
        runOnUiThread(() -> {
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

    boolean overlayVisible = false;

    private ActivityResultLauncher<Intent> wallImagePickerLauncher;
    private ActivityResultLauncher<Intent> wallVideoPickerLauncher;
    private ActivityResultLauncher<Intent> wallCameraLauncher;
    private WeakReference<WallPage> wallTargetRef;

    private WeakReference<ProfilePage> profileTargetRef;
    private ActivityResultLauncher<Intent> profilePickMediaLauncher;
    private ActivityResultLauncher<Intent> profileTakePhotoLauncher;
    private ActivityResultLauncher<Intent> profileTakeVideoLauncher;

    private WeakReference<ChatPage> chatTargetRef;
    private ActivityResultLauncher<Intent> chatMediaPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        startHostService();

        Log.i(TAG, "onCreate");
        ThemeManager.init(this).applyNoActionBarTheme(this);
        super.onCreate(savedInstanceState);
        appliedTheme = ThemeManager.init(this).getTheme();

        setContentView(R.layout.activity_main);

        DeepLinkParser.Result dl = DeepLinkParser.parse(getIntent());
        address = dl.address == null ? "" : dl.address;
        name = dl.name == null ? "" : dl.name;
        if (address.equals(TorManager.getInstance(this).getID())) address = "";

        setupPreferenceListener();

        lightboxImageView = findViewById(R.id.lightbox);
        lightboxVideoView = findViewById(R.id.lightboxVideo);
        if (lightboxVideoView != null) {
//            lightboxVideoView.setUseController(false);
            lightboxVideoView.setVisibility(View.INVISIBLE);
            lightboxVideoView.setOnClickListener(null);
            lightboxVideoView.setClickable(false);
        }

        if (lightboxImageView != null) {
            lightboxImageView.setVisibility(View.INVISIBLE);
            lightboxImageView.setOnClickListener(null);
            lightboxImageView.setClickable(false);
        }

        lightbox = new LightboxController(
                findViewById(R.id.lightbox),      // ImageView
                (PlayerView) findViewById(R.id.lightboxVideo),
                new LightboxController.Host() {
                    @Override
                    public void pauseAvatarVideos() {
                        if (wallPage != null) wallPage.pauseAvatarVideos();
                    }

                    @Override
                    public void resumeAvatarVideos() {
                        if (wallPage != null) wallPage.resumeAvatarVideos();
                    }

                    @Override
                    public void onLightboxHidden() { /*no-op*/ }
                },
                findViewById(R.id.container)
        );

        db = ItemDatabase.getInstance(this);

        wallPage = new WallPage(this);
        friendPage = new FriendPage(this);

        if (address.isEmpty()) {
            requestPage = new RequestPage(this);
            conversationPage = new ConversationPage(this);
            pages = new BasePage[]{
                    wallPage,
                    friendPage,
                    requestPage,
                    conversationPage,
                    new ProfilePage(this),
                    new BlogPage(this),
                    new CloudPage(this),
                    new PrivacyPage(this),
            };
        } else {
            chatPage = new ChatPage(this);
            conversationPage = null;
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

        AppBarLayout appbar = findViewById(R.id.appbar);
        appbar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            if (verticalOffset == 0) {
                // повністю зверху
                appBarLayout.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // є скрол
                appBarLayout.setBackgroundColor(Color.RED);
            }
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        // Встановити білий tint для всіх іконок меню
        toolbar.setTitleTextColor(ThemeManager.getColor(this, android.R.attr.actionMenuTextColor));
        toolbar.setSubtitleTextColor(ThemeManager.getColor(this, android.R.attr.actionMenuTextColor));
        if (toolbar.getOverflowIcon() != null) {
            toolbar.getOverflowIcon().setColorFilter(
                    ThemeManager.getColor(this, android.R.attr.actionMenuTextColor),
                    PorterDuff.Mode.SRC_ATOP
            );
        }
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null && !address.isEmpty()) {
            setTitle(address);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Set up the ViewPager with the sections adapter.
        viewPager = (ViewPager) findViewById(R.id.container);
        viewPager.setClipToPadding(false);
        viewPager.setPageMargin(20);

        PagerAdapter pagerAdapter = new PagerAdapter() {
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

        };

        viewPager.setAdapter(pagerAdapter);

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            int previous = -1;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                // Це викликається під час свайпу (між сторінками), якщо треба "в процесі"
            }

            @Override
            public void onPageSelected(int position) {
                // 🔹 Тут ти отримаєш swipe (перехід на сторінку)
                Log.d("ViewPager", "Page selected: " + position);

                // pause попередню
                if (previous >= 0) {
                    BasePage prev = pages[previous];
                    if (prev instanceof ProfilePage) ((ProfilePage) prev).onPagePause();
                }
                // resume поточну
                BasePage curr = pages[position];
                if (curr instanceof ProfilePage) ((ProfilePage) curr).onPageResume();

                previous = position;

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
        if (dimOverlay != null) {
            dimOverlay.setVisibility(View.VISIBLE);
            dimOverlay.setAlpha(1f);
            dimOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> dimOverlay.setVisibility(View.GONE));
        }
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

        applyUiCustomization();
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

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                boolean imgVisible = lightboxImageView != null && lightboxImageView.getVisibility() == View.VISIBLE;
                boolean vidVisible = lightboxVideoView != null && lightboxVideoView.getVisibility() == View.VISIBLE;

                if (imgVisible || vidVisible) {
                    lightbox.hideAll();
                    return;
                }
                setEnabled(false);
                MainActivity.super.onBackPressed();
            }
        });

        qrLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                activityResult -> {
                    if (activityResult.getResultCode() != RESULT_OK) return;
                    Intent data = activityResult.getData();
                    if (data == null) return;

                    Bundle extras = data.getExtras();
                    if (extras == null) return;

                    Bitmap bitmap = (Bitmap) extras.get("data");
                    if (bitmap == null) return;

                    int width = bitmap.getWidth(), height = bitmap.getHeight();
                    int[] pixels = new int[width * height];
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                    bitmap.recycle();

                    RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                    BinaryBitmap bBitmap = new BinaryBitmap(new HybridBinarizer(source));
                    MultiFormatReader reader = new MultiFormatReader();

                    try {
                        Result qrResult = reader.decode(bBitmap); // ← інша назва
                        String str = qrResult.getText();
                        Log.i("ID", str);

                        String[] tokens = str.split(" ", 3);

                        if (tokens.length < 2 || !tokens[0].equals("network.onion")) {
                            snack(getString(R.string.snackbar_qr_invalid_incompatible));
                            return;
                        }

                        String id = tokens[1].toLowerCase();
                        if (id.length() != 16) {
                            snack(getString(R.string.snackbar_qr_invalid));
                            return;
                        }

                        String name = tokens.length > 2 ? tokens[2] : "";
                        contactDialog(id, name);

                    } catch (Exception ex) {
                        snack(getString(R.string.snackbar_qr_invalid));
                        ex.printStackTrace();
                    }
                }
        );

        mediaPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Uri imageUri = result.getData().getData();
            if (imageUri == null) return;
            BasePage page = currentPage();
            if (page instanceof BlogPage) {
                launchPostActivityWithImageUri(imageUri);
                return;
            }
            if (wallPage != null) {
                try (java.io.InputStream is = getContentResolver().openInputStream(imageUri)) {
                    Bitmap image = BitmapFactory.decodeStream(is);
                    if (image != null) {
                        wallPage.writePost(null, image);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });

        cameraForBlogLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Object data = result.getData().getExtras() != null ? result.getData().getExtras().get("data") : null;
            Bitmap bmp = data instanceof Bitmap ? (Bitmap) data : null;
            if (bmp != null) {
                BasePage page = currentPage();
                if (page instanceof BlogPage) {
                    launchPostActivityWithImageBitmap(bmp);
                } else if (wallPage != null) {
                    wallPage.writePost(null, bmp);
                }
            }
        });

        wallImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    WallPage page = wallTargetRef != null ? wallTargetRef.get() : null;
                    if (page == null) return;
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        page.handleComposerActivityResult(
                                onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST,
                                RESULT_CANCELED,
                                result.getData()
                        );
                        return;
                    }
                    page.handleComposerActivityResult(
                            onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST,
                            RESULT_OK,
                            result.getData()
                    );
                });

        wallVideoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    WallPage page = wallTargetRef != null ? wallTargetRef.get() : null;
                    if (page == null) return;
                    page.handleComposerActivityResult(
                            onion.network.helpers.Const.REQUEST_PICK_VIDEO_POST,
                            result.getResultCode(),
                            result.getData()
                    );
                });

        wallCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    WallPage page = wallTargetRef != null ? wallTargetRef.get() : null;
                    if (page == null) return;
                    // Для камери ми кладемо фото в EXTRA_OUTPUT (page.pendingPhotoUri), тому data може бути null.
                    page.handleComposerActivityResult(
                            onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST,
                            result.getResultCode(),
                            result.getData()
                    );
                });

        profilePickMediaLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            ProfilePage page = profileTargetRef != null ? profileTargetRef.get() : null;
            if (page == null) return;
            page.handleProfileActivityResult(REQUEST_CHOOSE_MEDIA, result.getResultCode(), result.getData());
        });
        profileTakePhotoLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            ProfilePage page = profileTargetRef != null ? profileTargetRef.get() : null;
            if (page == null) return;
            page.handleProfileActivityResult(REQUEST_TAKE_PHOTO_PROFILE, result.getResultCode(), result.getData());
        });
        profileTakeVideoLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            ProfilePage page = profileTargetRef != null ? profileTargetRef.get() : null;
            if (page == null) return;
            page.handleProfileActivityResult(REQUEST_TAKE_VIDEO, result.getResultCode(), result.getData());
        });
        chatMediaPickerLauncher =
                registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                    ChatPage page = chatTargetRef != null ? chatTargetRef.get() : null;
                    if (page == null) return;
                    page.onChatMediaPickResult(result.getResultCode(), result.getData());
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Choreographer.getInstance().postFrameCallback(menuWatcher);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Choreographer.getInstance().removeFrameCallback(menuWatcher);
        if (overlayVisible) {
            overlayVisible = false;
            fadeOverlay(dimOverlay, false);
        }
    }

    @Nullable
    public static Uri extractSingleUri(@Nullable Intent data) {
        if (data == null) return null;
        Uri u = data.getData();
        if (u != null) return u;
        ClipData cd = data.getClipData();
        if (cd != null && cd.getItemCount() > 0) {
            return cd.getItemAt(0).getUri();
        }
        return null;
    }

    public void launchWallImagePicker(WallPage page) {
        wallTargetRef = new WeakReference<>(page);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        wallImagePickerLauncher.launch(intent);
    }

    public void launchWallVideoPicker(WallPage page) {
        wallTargetRef = new WeakReference<>(page);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        wallVideoPickerLauncher.launch(intent);
    }

    public void launchWallCamera(WallPage page, Uri output) {
        wallTargetRef = new WeakReference<>(page);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        wallCameraLauncher.launch(intent);
    }

    public void launchProfilePickMedia(@NonNull ProfilePage page, boolean photo) {
        profileTargetRef = new WeakReference<>(page);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(photo ? "image/*" : "video/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        profilePickMediaLauncher.launch(intent);
    }

    public void launchProfileCapturePhoto(@NonNull ProfilePage page) {
        profileTargetRef = new WeakReference<>(page);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        profileTakePhotoLauncher.launch(i);
    }

    public void launchProfileCaptureVideo(@NonNull ProfilePage page, @NonNull Uri outputUri, int maxSeconds, long maxBytes) {
        profileTargetRef = new WeakReference<>(page);
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                .putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxSeconds)
                .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
                .putExtra(MediaStore.EXTRA_FINISH_ON_COMPLETION, true)
                .putExtra(MediaStore.EXTRA_SIZE_LIMIT, maxBytes)
                .putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        profileTakeVideoLauncher.launch(intent);
    }

    public void openChatMediaPicker(onion.network.ui.pages.ChatPage page) {
        chatTargetRef = new java.lang.ref.WeakReference<>(page);

        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);

        try {
            chatMediaPickerLauncher.launch(intent);
        } catch (Exception ex) {
            ex.printStackTrace();
            snack(getString(onion.network.R.string.chat_attachment_pick_failed));
        }
    }

    /**
     * Детектим, чи є зараз popup overflow меню серед глобальних в'юшок WindowManager
     */
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
        } catch (Throwable ignored) {
        }
        return false;
    }

    private final Choreographer.FrameCallback menuWatcher = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
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
        ContextCompat.startForegroundService(this, new Intent(this, HostService.class));
//        startService(new Intent(this, HostService.class));
    }

    void showEnterId() {
        View dialogView = getLayoutInflater().inflate(R.layout.friend_dialog, null);
        final EditText addressEdit = (EditText) dialogView.findViewById(R.id.address);
        AlertDialog dialog = DialogHelper.themedBuilder(MainActivity.this)
                .setTitle(R.string.dialog_enter_id_title)
                .setView(dialogView)
                .setNegativeButton(R.string.dialog_button_cancel, (d, which) -> {
                })
                .setPositiveButton(R.string.dialog_button_open, (d, which) -> {
                    final String address = addressEdit.getText().toString().trim().toLowerCase();
                    if (address.length() > 56) {
                        snack(getString(R.string.snackbar_invalid_id));
                        return;
                    }
                    startActivity(new Intent(MainActivity.this, MainActivity.class).putExtra("address", address));

                })
                .create();
        DialogHelper.show(dialog);
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
        String[] menuItems = getResources().getStringArray(R.array.dialog_add_friend_options);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dialog_single_item, menuItems);

        AlertDialog dialog = DialogHelper.themedBuilder(this)
                .setTitle(R.string.dialog_add_friend_title)
                .setSingleChoiceItems(adapter, 0, (dialog1, which) -> {
                    if (which == 0) scanQR();
                    if (which == 1) showQR();

                    if (which == 2) showEnterId();
                    if (which == 3) showId();

                    if (which == 4) showUrl();

                    if (which == 5) inviteFriend();
                })
                .create();
        DialogHelper.show(dialog);
    }

    void scanQR() {
        qrLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
    }

    public String getAppName() {
        return Utils.getAppName(this);
    }

    void contactDialog(String id, String name) {

        boolean isFriend = db.hasKey("friend", id);

        if (name.isEmpty()) {
            name = getString(R.string.label_anonymous);
        }

        if (isFriend) {
            name += getString(R.string.dialog_contact_friend_suffix);
        }

        AlertDialog.Builder a = DialogHelper.themedBuilder(this)
                .setTitle(name)
                .setMessage(id);

        prefetch(this, id);

        final String n = name;
        final String i = id;

        if (!isFriend) {
            a.setPositiveButton(R.string.dialog_button_add_friend, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    addFriend(i, n);
                    viewPager.setCurrentItem(1, true);
                    contactDialog(i, n);
                }
            });
        }

        a.setNeutralButton(R.string.dialog_button_close, null);

        a.setNegativeButton(R.string.dialog_button_view_profile, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent(MainActivity.this, MainActivity.class).putExtra("address", i));
            }
        });

        DialogHelper.show(a);
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
        AlertDialog dialog = DialogHelper.themedBuilder(this)
                .setView(view)
                .create();
        DialogHelper.show(dialog);
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
        if (getSupportActionBar() == null) return;
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.handleOnRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    public BasePage currentPage() {
        if (viewPager == null) return null;
        int index = viewPager.getCurrentItem();
        return pages[index];
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
                    fab.setOnClickListener(v -> currentPage().onFab());
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

    private void setupPreferenceListener() {
        preferences = Settings.getPrefs(this);
        preferenceListener = (sharedPreferences, key) -> {
            if (!isUiCustomizationKey(key)) return;
            runOnUiThread(() -> {
                applyUiCustomization();
                notifyPagesCustomizationChanged();
            });
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    private boolean isUiCustomizationKey(String key) {
        return key != null && key.startsWith("ui_");
    }

    private void notifyPagesCustomizationChanged() {
        if (friendPage != null) {
            friendPage.refreshAppearance();
        }
        if (wallPage != null) {
            wallPage.refreshAppearance();
        }
        if (conversationPage != null) {
            conversationPage.refreshAppearance();
        }
        if (chatPage != null) {
            chatPage.applyUiCustomizationFromHost();
        }
    }

    private void applyUiCustomization() {
        UiCustomizationManager.FabPosition callPosition = UiCustomizationManager.getCallFabPosition(this);
        applyFabPosition((FloatingActionButton) findViewById(R.id.wallFab), callPosition);
        applyFabPosition((FloatingActionButton) findViewById(R.id.friendFab), callPosition);

        UiCustomizationManager.FabPosition menuPosition =
                resolveMenuFabPosition(callPosition, UiCustomizationManager.getMenuButtonPosition(this));
        applyMenuFabPosition(menuPosition);

        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(this);
        applyFabPalette((FloatingActionButton) findViewById(R.id.wallFab), preset);
        applyFabPalette((FloatingActionButton) findViewById(R.id.friendFab), preset);
        applyFabPalette(menuFab, preset);

        if (arcButtonLayout != null && pages != null) {
            for (int i = 0; i < pages.length; i++) {
                FloatingActionButton tabFab = arcButtonLayout.findViewById(100 + i);
                applyFabPalette(tabFab, preset);
            }
        }
    }

    private void applyFabPalette(FloatingActionButton fab, UiCustomizationManager.ColorPreset preset) {
        if (fab == null || preset == null) return;
        ColorStateList backgroundTint = ColorStateList.valueOf(preset.getAccentColor(this));
        ColorStateList iconTint = ColorStateList.valueOf(preset.getOnAccentColor(this));
        fab.setBackgroundTintList(backgroundTint);
        fab.setImageTintList(iconTint);
    }

    private UiCustomizationManager.FabPosition resolveMenuFabPosition(
            UiCustomizationManager.FabPosition callPosition,
            UiCustomizationManager.FabPosition desiredMenuPosition) {
        if (desiredMenuPosition == null) return null;
        if (callPosition == null || callPosition != desiredMenuPosition) {
            return desiredMenuPosition;
        }

        UiCustomizationManager.FabPosition[] candidates = {
                UiCustomizationManager.FabPosition.BOTTOM_START,
                UiCustomizationManager.FabPosition.CENTER_BOTTOM,
                UiCustomizationManager.FabPosition.BOTTOM_END
        };

        for (UiCustomizationManager.FabPosition candidate : candidates) {
            if (candidate != callPosition) {
                return candidate;
            }
        }
        return desiredMenuPosition;
    }

    private void applyFabPosition(FloatingActionButton fab, UiCustomizationManager.FabPosition position) {
        if (fab == null || position == null) return;
        ViewGroup.LayoutParams lp = fab.getLayoutParams();
        if (!(lp instanceof CoordinatorLayout.LayoutParams)) return;
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) lp;
        int margin = UiCustomizationManager.dpToPx(this, 16);
        params.setMargins(margin, margin, margin, margin);
        switch (position) {
            case TOP_START:
                params.gravity = Gravity.TOP | Gravity.START;
                break;
            case TOP_END:
                params.gravity = Gravity.TOP | Gravity.END;
                break;
            case CENTER_TOP:
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                break;
            case CENTER_BOTTOM:
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                break;
            case BOTTOM_START:
                params.gravity = Gravity.BOTTOM | Gravity.START;
                break;
            case BOTTOM_END:
            default:
                params.gravity = Gravity.BOTTOM | Gravity.END;
                break;
        }
        fab.setLayoutParams(params);
    }

    private void applyMenuFabPosition(UiCustomizationManager.FabPosition position) {
        if (arcButtonLayout == null || position == null) return;
        int mapped;
        switch (position) {
            case CENTER_BOTTOM:
                mapped = 1;
                break;
            case BOTTOM_END:
                mapped = 2;
                break;
            case BOTTOM_START:
            default:
                mapped = 0;
                break;
        }
        arcButtonLayout.setFabPosition(mapped);
    }

    void updateMenu() {
        invalidateOptionsMenu();
    }

    public void toggleChatMainMenu() {
        menuState.call = currentPage() instanceof ChatPage;
        updateMenu();
    }

    public void togglePostMainMenu() {
        if (currentPage() instanceof BlogPage) {
            menuState = MenuState.blog();
        } else {
            menuState = MenuState.normal();
            menuState.call = currentPage() instanceof ChatPage;
        }
        updateMenu();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_photo).setVisible(menuState.photo);
        menu.findItem(R.id.action_camera).setVisible(menuState.camera);
        menu.findItem(R.id.action_blog_title).setVisible(menuState.blogTitle);
        menu.findItem(R.id.action_share).setVisible(menuState.share);
        menu.findItem(R.id.action_home).setVisible(menuState.home);
        menu.findItem(R.id.action_add_post).setVisible(menuState.addPost);
        menu.findItem(R.id.action_style).setVisible(menuState.style);

        menu.findItem(R.id.action_call).setVisible(menuState.call);
        menu.findItem(R.id.action_refresh).setVisible(menuState.refreshQr);
        menu.findItem(R.id.action_menu_scan_qr).setVisible(menuState.scanQr);
        menu.findItem(R.id.action_menu_show_my_qr).setVisible(menuState.showMyQr);
        menu.findItem(R.id.action_menu_enter_id).setVisible(menuState.enterId);
        menu.findItem(R.id.action_menu_show_my_id).setVisible(menuState.showMyId);
        menu.findItem(R.id.action_menu_show_uri).setVisible(menuState.showUri);
        menu.findItem(R.id.action_menu_invite_friends).setVisible(address.isEmpty() && menuState.inviteFriends);

        menu.findItem(R.id.action_add_friend).setVisible(!address.isEmpty() && !db.hasKey("friend", address));
        menu.findItem(R.id.action_friends).setVisible(!address.isEmpty() && db.hasKey("friend", address));
        menu.findItem(R.id.action_clear_chat).setVisible(!address.isEmpty());
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

        if (id == R.id.action_call) {
            snack("Available soon");
        }
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
            mediaPickerLauncher.launch(Intent.createChooser(intent, "Complete action using"));
            return true;
        }
        if (id == R.id.action_camera) {
            PermissionHelper.runWithPermissions(
                    this,
                    EnumSet.of(
                            PermissionHelper.PermissionRequest.CAMERA,
                            PermissionHelper.PermissionRequest.MEDIA
                    ),
                    () -> cameraForBlogLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
                    () -> snack("Camera permission required")
            );
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
            AlertDialog dialog = DialogHelper.themedBuilder(this)
                    .setTitle(R.string.dialog_friends_title)
                    .setMessage(R.string.dialog_friends_message)
                    .setNeutralButton(R.string.dialog_button_remove_friend, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FriendTool.getInstance(MainActivity.this).unfriend(address);
                            load();
                            snack(getString(R.string.snackbar_contact_removed));
                        }
                    })
                    .create();
            DialogHelper.show(dialog);
            return true;
        }
        if (id == R.id.action_add_friend) {
            addFriend(address, name);
            snack(getString(R.string.snackbar_friend_added));
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
        if (id == R.id.action_menu_themes) {
            startActivity(new Intent(this, ThemeSettingsActivity.class));
            return true;
        }
        if (id == R.id.action_clear_chat) {
            DialogHelper.showConfirm(
                    this,
                    R.string.dialog_clear_chat_title,
                    R.string.dialog_clear_chat_message,
                    R.string.dialog_button_yes,
                    () -> {
                        ChatDatabase.getInstance(MainActivity.this).clearChat(address);
                        toast(getString(R.string.toast_chat_cleared));
                        chatPage.load();
                    },
                    R.string.dialog_button_no,
                    null
            );
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
            Toast.makeText(this, getString(R.string.toast_error), Toast.LENGTH_SHORT).show();
        }
    }

    public void showId() {
        final String id = getID();
        AlertDialog dialog = DialogHelper.themedBuilder(this)
                .setTitle(getString(R.string.dialog_show_id_title, id))
                .setNegativeButton(R.string.dialog_button_copy, (dialog1, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("id", id));
                    snack(getString(R.string.snackbar_id_copied));
                })
                .setPositiveButton(R.string.dialog_button_send, (dialog2, which) -> startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, id).setType("text/plain")))
                .create();
        DialogHelper.show(dialog);
    }

    void showUrl() {

        final View v = getLayoutInflater().inflate(R.layout.url_dialog, null);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        {
            final String onionLink = String.format("%s.onion/network.onion", getID());
            ((TextView) v.findViewById(R.id.onion_link_text)).setText(onionLink);
            v.findViewById(R.id.onion_link_copy).setOnClickListener(v1 -> {
                cm.setPrimaryClip(ClipData.newPlainText("onion_link", onionLink));
                toast(getString(R.string.toast_link_copied));
            });
            v.findViewById(R.id.onion_link_share).setOnClickListener(v2 -> startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "http://" + onionLink).setType("text/plain")));
            v.findViewById(R.id.onion_link_view).setOnClickListener(v3 -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + onionLink))));
        }

        {
            final String clearnetLink = String.format("%s.onion.to/network.onion", getID());
            ((TextView) v.findViewById(R.id.clearnet_link_text)).setText(clearnetLink);
            v.findViewById(R.id.clearnet_link_copy).setOnClickListener(v4 -> {
                cm.setPrimaryClip(ClipData.newPlainText("clearnet_link", clearnetLink));
                toast(getString(R.string.toast_link_copied));
            });
            v.findViewById(R.id.clearnet_link_share).setOnClickListener(v5 -> startActivity(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "http://" + clearnetLink).setType("text/plain")));
            v.findViewById(R.id.clearnet_link_view).setOnClickListener(v6 -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + clearnetLink))));
        }

        AlertDialog dialog = DialogHelper.themedBuilder(this)
                .setView(v)
                .create();
        DialogHelper.show(dialog);

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

    public void snack(String str) {
        Snackbar.make(viewPager, str, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String currentTheme = ThemeManager.init(this).getTheme();
        if (appliedTheme != null && !appliedTheme.equals(currentTheme)) {
            appliedTheme = currentTheme;
            recreate();
            return;
        }
        applyUiCustomization();
        notifyPagesCustomizationChanged();
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

            if (timer != null) {
                timer.cancel();
                timer.purge();
                timer = null;
            }

            for (BasePage page : pages) {
                page.onPause();
            }
        } finally {
            if (instance == this) instance = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (lightbox != null) lightbox.release();
        super.onDestroy();
        if (preferences != null && preferenceListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        TorManager.getInstance(this).stopTor();
    }

    public void lightbox(Bitmap bitmap) {
        if (bitmap == null) return;
        lightbox.hideAll();
        lightbox.showImage(bitmap);
    }

    public void lightboxVideo(Uri videoUri, Bitmap preview) {
        if (videoUri == null) return;
        lightbox.hideAll();
        lightbox.showVideo(videoUri, preview);
    }

    public void showLightbox(AvatarView.AvatarContent content) {
        if (content.isVideo()) {
            lightbox.showVideo(Uri.parse(content.videoUri), content.preview);
        } else if (content.photo != null) {
            lightbox.showImage(content.photo);
        }
    }

    void inviteFriend() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);

        intent.putExtra(Intent.EXTRA_TEXT, String.format(getString(R.string.invitation_text), TorManager.getInstance(this).getID(), Uri.encode(getName()), getAppName()));
        intent.setType("text/plain");

        startActivity(intent);
    }

    private void launchPostActivityWithImageUri(Uri uri) {
        if (uri == null) {
            return;
        }
        Intent intent = new Intent(this, PostActivity.class);
        intent.putExtra(PostActivity.EXTRA_INITIAL_IMAGE_URI, uri.toString());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void launchPostActivityWithImageBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        Intent intent = new Intent(this, PostActivity.class);
        intent.putExtra(PostActivity.EXTRA_INITIAL_IMAGE_BITMAP, bitmap);
        startActivity(intent);
    }

}
