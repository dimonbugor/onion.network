

package onion.network.ui.pages;

import static onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;

import onion.network.helpers.ThemeManager;
import onion.network.helpers.PermissionHelper;
import onion.network.models.Item;
import onion.network.models.ItemTask;
import onion.network.R;
import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

public class WallPage extends BasePage {

    LinearLayout contentView;
    View wallScroll;
    int count = 5;
    String TAG = "WallPage";
   String postEditText = null;

   String smore;
   int imore;
    View vmore, fmore;
    private Uri pendingPhotoUri;


    public WallPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.wall_page, this, true);
        contentView = (LinearLayout) findViewById(R.id.contentView);
        //load();

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
        return "Wall";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_format_list_bulleted;
    }

    @Override
    public void load() {
        load(0, "");
    }

    @Override
    public int getFab() {
        return R.id.wallFab;
    }

    @Override
    public void onFab() {
        writePost(null, null);
    }

    void getData(JSONObject o, View dlg) {
        try {
            o.put("text", ((EditText) dlg.findViewById(R.id.text)).getText().toString());
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }

    void doPostPublish(Item item, String text, Bitmap bitmap, View dialogView) {
        if (item != null) {
            JSONObject o = item.json();
            getData(o, dialogView);
            Item item2 = new Item(item.type(), item.key(), item.index(), o);
            ItemDatabase.getInstance(getContext()).put(item2);
            activity.load();
        } else {
            JSONObject data = new JSONObject();
            getData(data, dialogView);
            try {
                data.put("date", "" + System.currentTimeMillis());
                if (bitmap != null) {
                    data.put("img", Utils.encodeImage(bitmap));
                }
            } catch (JSONException ex) {
                throw new RuntimeException(ex);
            }
            activity.publishPost(data);
            activity.load();
        }
    }

    @Override
    public void onResume() {
        postEditText = null;
    }

    void doPost(final Item item, final String text, Bitmap bitmap) {

        final View dialogView = activity.getLayoutInflater().inflate(R.layout.wall_dialog, null);
        final EditText textEdit = (EditText) dialogView.findViewById(R.id.text);

        if (item != null) {
            textEdit.setText(item.json().optString("text"));
            bitmap = item.bitmap("img");
        }

        if (text != null) {
            textEdit.setText(text);
        }

        if (bitmap != null) {
            ((ImageView) dialogView.findViewById(R.id.image)).setImageBitmap(bitmap);
        } else {
            dialogView.findViewById(R.id.image).setVisibility(View.GONE);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity)).setView(dialogView);

        final Bitmap bmp = bitmap;

        if (item != null) {
            b.setTitle("Edit Post");
        } else {
            b.setTitle("Write Post");
        }

        b.setCancelable(true);

        final Dialog d = b.create();

        dialogView.findViewById(R.id.publish).setOnClickListener(v -> {
            doPostPublish(item, text, bmp, dialogView);
            d.cancel();
        });

        if (item == null) {
            dialogView.findViewById(R.id.take_photo).setOnClickListener(v -> {
                postEditText = textEdit.getText().toString();
                PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.CAMERA),
                        () -> {
                            try {
                                pendingPhotoUri = createPhotoOutputUri();
                            } catch (IOException ex) {
                                pendingPhotoUri = null;
                                activity.snack("Unable to create photo file");
                                return;
                            }

                            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
                            takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            d.cancel();
                            activity.startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO_POST);
                        },
                        () -> activity.snack("Camera permission required"));
            });
            dialogView.findViewById(R.id.add_image).setOnClickListener(v -> {
                postEditText = textEdit.getText().toString();
                PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.MEDIA),
                        () -> {
                            d.cancel();
                            startImageChooser(REQUEST_PICK_IMAGE_POST);
                        },
                        () -> activity.snack("Storage permission required"));
            });
        } else {
            dialogView.findViewById(R.id.take_photo).setVisibility(View.GONE);
            dialogView.findViewById(R.id.add_image).setVisibility(View.GONE);
        }

        d.show();

    }

    void editPost(final Item item) {

        doPost(item, null, null);

    }

    public void writePost(final String text, final Bitmap bitmap) {

        doPost(null, text, bitmap);

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            deleteTempPhoto();
            return;
        }

        Bitmap bmp = null;

        if (requestCode == REQUEST_PICK_IMAGE_POST) {
            Uri uri = data.getData();
            bmp = getActivityResultBitmap(data);
            bmp = fixImageOrientation(bmp, uri);
        }

        if (requestCode == REQUEST_TAKE_PHOTO_POST) {
            Uri uri = pendingPhotoUri;
            if (uri != null) {
//                bmp = decodeCapturedPhoto(uri);
                bmp = fixImageOrientation(bmp, uri);
            } else if (data != null && data.getExtras() != null) {
                bmp = (Bitmap) data.getExtras().get("data");
            }
        }

        if (bmp == null) {
            deleteTempPhoto();
            return;
        }

        int maxdim = 1080; // 🔼 Підвищено до 1080 для кращої якості

        if (bmp.getWidth() >= bmp.getHeight() && bmp.getWidth() > maxdim) {
            bmp = Bitmap.createScaledBitmap(
                    bmp,
                    maxdim,
                    (int) ((double) bmp.getHeight() * maxdim / bmp.getWidth()),
                    true
            );
        } else if (bmp.getHeight() > maxdim) {
            bmp = Bitmap.createScaledBitmap(
                    bmp,
                    (int) ((double) bmp.getWidth() * maxdim / bmp.getHeight()),
                    maxdim,
                    true
            );
        }

        writePost(postEditText, bmp);
        postEditText = null;
        deleteTempPhoto();
    }


    void load(final int i, String s) {

        Log.i(TAG, "load: ");

        new ItemTask(getContext(), address, "post", s, count) {

            void fill(ItemResult itemResult, boolean finished) {

                String myAddress = TorManager.getInstance(context).getID();

                String wallAddress = address;
                if (wallAddress.isEmpty()) wallAddress = myAddress;

                if (contentView.getChildCount() > i) {
                    contentView.removeViews(i, contentView.getChildCount() - i);
                }

                for (int i = 0; i < itemResult.size(); i++) {

                    final Item item = itemResult.at(i);

                    Log.i("Item", item.text());

                    final JSONObject o = item.json(getContext(), address);

                    View v = activity.getLayoutInflater().inflate(R.layout.wall_item, contentView, false);

                    View link = v.findViewById(R.id.link);
                    View thumblink = v.findViewById(R.id.thumblink);
                    TextView address = ((TextView) v.findViewById(R.id.address));
                    TextView name = ((TextView) v.findViewById(R.id.name));
                    TextView date = ((TextView) v.findViewById(R.id.date));
                    TextView text = ((TextView) v.findViewById(R.id.text));
                    ImageView like = ((ImageView) v.findViewById(R.id.like));
                    ImageView comments = ((ImageView) v.findViewById(R.id.comments));
                    ImageView share = ((ImageView) v.findViewById(R.id.share));
                    ImageView delete = ((ImageView) v.findViewById(R.id.delete));
                    ImageView edit = ((ImageView) v.findViewById(R.id.edit));
                    AvatarView thumb = (AvatarView) v.findViewById(R.id.thumb);
                    ImageView image = ((ImageView) v.findViewById(R.id.image));

                    Bitmap photoThumb = item.bitmap("thumb");
                    Bitmap videoThumb = item.bitmap("video_thumb");
                    String videoUri = o.optString("video", "").trim();
                    thumb.bind(photoThumb, videoThumb, videoUri.isEmpty() ? null : videoUri);

                    image.setVisibility(View.GONE);
                    try {
                        String str = o.optString("img");
                        str = str.trim();
                        if (!str.isEmpty()) {
                            byte[] photodata = Base64.decode(str, Base64.DEFAULT);
                            if (photodata.length > 0) {
                                final Bitmap bitmap = BitmapFactory.decodeByteArray(photodata, 0, photodata.length);
                                image.setImageBitmap(bitmap);
                                image.setVisibility(View.VISIBLE);
                                image.setOnClickListener(new OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        activity.lightbox(bitmap);
                                    }
                                });
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    final String postAddress = o.optString("addr");

                    String n = postAddress;


                    String m = o.optString("name", "");
                    if (!m.isEmpty()) name.setText(m);

                    address.setText(n);

                    text.setMovementMethod(LinkMovementMethod.getInstance());

                    String t = o.optString("text");
                    text.setText(Utils.linkify(context, t));
                    if (t.isEmpty()) text.setVisibility(View.GONE);

                    String datestr = Utils.formatDate(o.optString("date"));
                    date.setText(datestr);

                    if (!postAddress.equals(wallAddress)) {
                        address.setPaintFlags(address.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
                        name.setPaintFlags(address.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
                        link.setClickable(true);
                        link.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                getContext().startActivity(new Intent(getContext(), MainActivity.class).putExtra("address", postAddress));
                            }
                        });
                        thumblink.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                getContext().startActivity(new Intent(getContext(), MainActivity.class).putExtra("address", postAddress));
                            }
                        });
                    }

                    like.setClickable(true);
                    like.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            activity.snack("Available soon");
                        }
                    });

                    comments.setClickable(true);
                    comments.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            activity.snack("Available soon");
                        }
                    });

                    if (wallAddress.equals(myAddress)) {
                        delete.setClickable(true);
                        delete.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                AlertDialog dialogDeletePost = new AlertDialog.Builder(context, ThemeManager.getDialogThemeResId(context))
                                        .setTitle("Delete Post?")
                                        .setMessage("Do you really want to delete this post?")
                                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                ItemDatabase.getInstance(context).delete(item.type(), item.key());
                                                load();
                                            }
                                        })
                                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                            }
                                        })
                                        .create();
                                dialogDeletePost.setOnShowListener(d -> {
                                    dialogDeletePost.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                    dialogDeletePost.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                });
                                dialogDeletePost.show();
                            }
                        });
                        delete.setVisibility(View.VISIBLE);
                    } else {
                        delete.setVisibility(View.GONE);
                    }

                    if (wallAddress.equals(myAddress) && postAddress.equals(myAddress)) {
                        edit.setClickable(true);
                        edit.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                editPost(item);
                            }
                        });
                        edit.setVisibility(View.VISIBLE);
                    } else {
                        edit.setVisibility(View.GONE);
                    }

                    if (!wallAddress.equals(myAddress) && !postAddress.equals(myAddress) && "".equals(o.optString("access"))) {
                        share.setVisibility(View.VISIBLE);
                        share.setClickable(true);
                        share.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                activity.sharePost(o);
                            }
                        });
                    } else {
                        share.setVisibility(View.GONE);
                    }

                    contentView.addView(v);
                }

                if (i == 0 && itemResult.size() == 0 && !itemResult.loading()) {
                    View v = activity.getLayoutInflater().inflate(R.layout.wall_empty, contentView, false);
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

    void loadMore() {
        if (smore == null) return;
        String smore2 = smore;
        smore = null;
        load(imore, smore2);
    }

    private Uri createPhotoOutputUri() throws IOException {
        File dir = new File(getContext().getExternalCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create directory for photo capture");
        }
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
        File file = new File(dir, name);
        if (!file.createNewFile()) {
            throw new IOException("Unable to create photo file");
        }
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
    }

//    private Bitmap decodeCapturedPhoto(@NonNull Uri uri) {
//        final int maxDimension = 2048;
//        BitmapFactory.Options options = new BitmapFactory.Options();
//        options.inJustDecodeBounds = true;
//        try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
//            BitmapFactory.decodeStream(is, null, options);
//        } catch (IOException ex) {
//            ex.printStackTrace();
//            return null;
//        }
//
//        options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension);
//        options.inJustDecodeBounds = false;
//        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
//
//        try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
//            return BitmapFactory.decodeStream(is, null, options);
//        } catch (IOException ex) {
//            ex.printStackTrace();
//        }
//        return null;
//    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private void deleteTempPhoto() {
        if (pendingPhotoUri == null) return;
        try {
            getContext().getContentResolver().delete(pendingPhotoUri, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pendingPhotoUri = null;
        }
    }

}
