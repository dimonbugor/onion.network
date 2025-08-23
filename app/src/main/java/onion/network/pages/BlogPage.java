package onion.network.pages;

import static android.content.Context.CLIPBOARD_SERVICE;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import onion.network.R;
import onion.network.TorManager;
import onion.network.helpers.Utils;
import onion.network.models.Blog;
import onion.network.models.Response;
import onion.network.ui.MainActivity;
import onion.network.ui.PostActivity;

public class BlogPage extends BasePage {

    String TAG = "BlogPage";

    LinearLayout contentView;
    WebView webView;
    Blog blog;
    String blogUrl = "http://blog";
    FloatingActionButton fab;

    public BlogPage(MainActivity activity) {
        super(activity);

        blog = Blog.getInstance(activity);

        activity.getLayoutInflater().inflate(R.layout.blog_page, this, true);

        contentView = (LinearLayout) findViewById(R.id.contentView);

        fab = findViewById(getFab());

        webView = (WebView) findViewById(R.id.webView);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void delete(final String id) {
                if (id == null) return;
                activity.runOnUiThread(() -> {
                    AlertDialog dialog = new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                            .setTitle("Remove Post?")
                            .setMessage("Do you really want to remove this post?")
                            .setPositiveButton("Yes", (dialog2, which) -> {
                                try {
                                    blog.deletePost(id);
                                    Snackbar.make(webView, "Post removed.", Snackbar.LENGTH_SHORT).show();
                                } catch (Exception ex) {
                                    Snackbar.make(webView, "Failed to remove post.", Snackbar.LENGTH_SHORT).show();
                                }
                                webView.reload();
                            })
                            .setNegativeButton("No", (dialog1, which) -> {
                            }).create();
                    dialog.setOnShowListener(d -> {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
                    });
                    dialog.show();
                });
            }

            @JavascriptInterface
            public void edit(final String id) {
                if (id == null) return;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //addPost(null, null, null, id);
                        editPost(id);
                    }
                });
            }

            @JavascriptInterface
            public void title() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        editTitle();
                    }
                });
            }
        }, "cms");

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setPluginState(WebSettings.PluginState.OFF);
        webView.getSettings().setBlockNetworkLoads(true);
        //webView.getSettings().setBlockNetworkImage(true);
//        webView.getSettings().setAppCacheEnabled(false);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient() {


        });
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                updateUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateUrl(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {


                String path = "";
                try {
                    path = new URL(url).getPath();
                } catch (MalformedURLException ex) {
                    ex.printStackTrace();
                }


                Response response = blog.getResponse(path, true);
                return new WebResourceResponse(
                        response.getContentType(),
                        "UTF-8",
                        new ByteArrayInputStream(response.getContent())
                );


                /*
                try {

                    LocalSocket s = new LocalSocket();
                    s.connect(new LocalSocketAddress(Server.getExistingInstance().getSocketName(), LocalSocketAddress.Namespace.FILESYSTEM));
                    OutputStreamWriter w = new OutputStreamWriter(s.getOutputStream());
                    w.write("GET " + path + " HTTP/1.0\r\n");
                    w.write("\r\n");
                    w.flush();
                    return new WebResourceResponse(
                            "text/html",
                            "utf-8",
                            s.getInputStream());

                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                return new WebResourceResponse(
                        "text/html",
                        "utf-8",
                        new ByteArrayInputStream("Error".getBytes()));
                */

            }
        });
    }

    @Override
    public String getTitle() {
        return "Blog";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_info;
    }

    @Override
    public void load() {
        goHome();
    }

    @Override
    public int getFab() {
        return R.id.wallFab;
    }

    @Override
    public void onFab() {
        String editId = getEditId(webView.getUrl());
        if (editId != null) {
            editPost(editId);
        } else {
            addPost();
        }
    }

    public void goHome() {
        webView.loadUrl(blogUrl);
    }
    void updateUrl(String url) {

        if (url == null) url = "";

        String editId = getEditId(url);

        if(fab != null){
            fab.setImageResource(
                    editId == null ?
                            R.drawable.ic_add_white_48dp :
                            R.drawable.ic_edit_white_36dp
            );

            fab.setVisibility(editId != null || Uri.parse(url).getPath().split("/").length < 3 ? View.VISIBLE : View.GONE);
        }

    }
    void update() {
        webView.reload();
        updateUrl(webView.getUrl());
    }
    String getEditId(String url) {

        if (url == null) url = "";

        String p = Uri.parse(url).getPath();

        Log.i("path", p);

        String[] pp = p.split("/");

        for (String s : pp) {
            Log.i("tok", pp.length + " " + s);
        }

        boolean x = (pp.length == 3 && pp[0].equals("") && pp[1].matches("^[0-9]+$") && pp[2].matches("^[a-zA-Z0-9-]+\\.htm$"));

        //boolean x = p.matches("^/[0-9]+/[a-zA-Z0-9-]+\\.htm$");

        if (x) {
            Log.i("editid", "editid");
            return pp[1];
        }

        return null;
    }
    public void editTitle() {
        final View view = activity.getLayoutInflater().inflate(R.layout.dialog_title, null);
        ((EditText) view.findViewById(R.id.title)).setText(blog.getTitle());
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle("Change Blog Title")
                .setView(view)
                .setPositiveButton("Publish", (dialog1, which) -> {
                    blog.setTitle(((EditText) view.findViewById(R.id.title)).getText().toString());
                    update();
                    //hidekey();
                    Snackbar.make(webView, "Title changed.", Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (dialog2, which) -> {
                    //hidekey();
                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
        // view.findViewById(R.id.title).requestFocus();
        //((EditText) view.findViewById(R.id.title)).selectAll();
        //showkey();
    }
    public void addPost() {
        activity.startActivity(new Intent(activity, PostActivity.class));
    }
    void editPost(String id) {
        activity.startActivity(new Intent(activity, PostActivity.class).putExtra("id", id));
    }
    public void share() {
        final String domain = TorManager.getInstance(activity).getOnion();
        final View view = activity.getLayoutInflater().inflate(R.layout.dialog_share, null);
        ((TextView) view.findViewById(R.id.darknet)).setText(domain);
        ((TextView) view.findViewById(R.id.clearnet)).setText(domain + ".to");

        ((TextView) view.findViewById(R.id.darkinfo)).setText(Html.fromHtml(
                "Anonymous and most secure way to access your blog. Needs a Tor-enabled web browser, such as <a href='https://play.google.com/store/apps/details?id=onion.fire'>Fire.onion</a>."));
        ((TextView) view.findViewById(R.id.darkinfo)).setClickable(true);
        ((TextView) view.findViewById(R.id.darkinfo)).setMovementMethod(LinkMovementMethod.getInstance());

        view.findViewById(R.id.clearcopy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("", "http://" + domain + ".to"));
            Toast.makeText(activity, "Clearnet link copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        view.findViewById(R.id.clearview).setOnClickListener(v -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + domain + ".to"))));
        view.findViewById(R.id.clearsend).setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, "http://" + domain + ".to");
            intent.setType("text/plain");
            activity.startActivity(intent);
        });

        view.findViewById(R.id.darkcopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("", "http://" + domain));
                Toast.makeText(activity, "Darknet link copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
        view.findViewById(R.id.darkview).setOnClickListener(v -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + domain))));
        view.findViewById(R.id.darksend).setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, "http://" + domain);
            intent.setType("text/plain");
            activity.startActivity(intent);
        });

        new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setView(view)
                .show();
    }
    public void showAbout() {
        String versionName = "";
        try {
            versionName = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle(activity.getString(R.string.app_name))
                //.setMessage(BuildConfig.APPLICATION_ID + "\n\nVersion: " + BuildConfig.VERSION_NAME)
                .setMessage("Version: " + versionName)
                .setNeutralButton("Libraries", (d, which) -> showLibraries())
                .setPositiveButton("OK", (d, which) -> {
                }).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }
    void showLibraries() {
        final String[] items;
        try {
            items = getResources().getAssets().list("licenses");
        } catch (IOException ex) {
            throw new Error(ex);
        }
        new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle("Third party software used in this app (click to view license)")
                .setItems(items, (dialog, which) -> showLicense(items[which]))
                .show();
    }
    void showLicense(String name) {
        String text;
        try {
            text = Utils.readInputStreamToString(getResources().getAssets().open("licenses/" + name));
        } catch (IOException ex) {
            throw new Error(ex);
        }
        new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle(name)
                .setMessage(text)
                .show();
    }
    public void selectStyle() {
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
                .setTitle("Choose Style")
                .setSingleChoiceItems(blog.getStyles(), blog.getStyleIndex(), (d, which) -> {
                    blog.setStyle(which);
                    update();
                    d.cancel();
                })
                .setNegativeButton("OK", (d, which) -> {
                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
        dialog.show();
    }
}
