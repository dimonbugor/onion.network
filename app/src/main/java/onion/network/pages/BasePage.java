

package onion.network.pages;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.FrameLayout;

import java.io.IOException;

import onion.network.models.Item;
import onion.network.databases.ItemDatabase;
import onion.network.ui.MainActivity;

public abstract class BasePage extends FrameLayout {

    public MainActivity activity;
    public String address;
    public Context context;
    public ItemDatabase itemDatabase;

    public BasePage(MainActivity activity) {

        super(activity);

        this.context = activity;

        this.activity = activity;

        this.address = activity.address;

        itemDatabase = ItemDatabase.getInstance(context);

    }

    protected boolean isPageShown() {
        MainActivity a = activity;
        if (a == null) a = MainActivity.getInstance();
        if (a == null) return false;
        return activity.currentPage() == this;
    }

    public void onTabSelected() {
        activity.togglePostMainMenu();
    }

    public String getBadge() {
        return null;
    }

    public void onNameItem(Item item) {
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    public void load() {
    }

    public abstract String getTitle();

    public int getFab() {
        return -1;
    }

    public void onFab() {
    }

    public void onPause() {
    }

    public void onResume() {
    }



    public int getIcon() {
        return 0;
    }

    public String getPageIDString() {
        return "";
    }

    public void toast(String s) {
        activity.toast(s);
    }

    public Bitmap getActivityResultBitmap(Intent intent) {
        try {
            return MediaStore.Images.Media.getBitmap(activity.getContentResolver(), intent.getData());
        } catch (IOException ex) {
            toast("File not found");
            return null;
        } catch(SecurityException ex) {
            toast("Access denied");
            return null;
        }
    }

    public Bitmap fixImageOrientation(Bitmap bitmap, Uri uri) {
        try {
            ExifInterface exif = new ExifInterface(activity.getContentResolver().openInputStream(uri));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            int rotation = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotation = 270;
                    break;
                default:
                    rotation = 0;
            }

            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private void log(String s) {
        activity.log(s);
    }

    protected void startImageChooser(int id) {
        log("image chooser " + id);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        //Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        //Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(intent, id);
    }

    protected void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    }
}
