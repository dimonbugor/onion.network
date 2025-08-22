/*
 * Blog.onion
 *
 * http://play.google.com/store/apps/details?id=onion.blog
 * http://onionapps.github.io/Blog.onion/
 * http://github.com/onionApps/Blog.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;

import onion.network.R;
import onion.network.models.Blog;

public class PostActivity extends AppCompatActivity {

    String id;
    EditText title;
    EditText content;
    ImageView image;
    Bitmap bitmap;
    Blog blog;
    int REQUEST_PICKER = 25;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_post);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Встановити білий tint для всіх іконок меню
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(Color.WHITE);
        toolbar.getOverflowIcon().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);

        // Увімкнути кнопку "назад"
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        blog = Blog.getInstance(this);

        title = (EditText) findViewById(R.id.title);
        content = (EditText) findViewById(R.id.content);
        image = (ImageView) findViewById(R.id.image);

        /*
        Intent intent = getIntent();

        title.setText(intent.getExtras().getString("title", ""));
        content.setText(intent.getExtras().getString("content", ""));

        Bitmap bmp = intent.get
        */

        id = getIntent().getStringExtra("id");

        if (id != null) {

            Blog.PostInfo postInfo = blog.getPostInfo(id);

            title.setText(postInfo.getTitle());
            content.setText(postInfo.getContent());

        }


        getSupportActionBar().setTitle(id != null ? "Edit" : "Create");

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_post, menu);
        menu.findItem(R.id.action_photo).setVisible(id == null);
        //menu.findItem(R.id.action_photo).setEnabled(id == null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (item.getItemId() == R.id.action_publish) {

            if (id != null) {
                blog.updatePost(id, title.getText().toString(), content.getText().toString());
            } else {
                blog.addPost(title.getText().toString(), content.getText().toString(), bitmap);
            }
            finish();
            return true;
        }

        if (item.getItemId() == R.id.action_photo) {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICKER);
            return true;
        }

        if (item.getItemId() == android.R.id.home) {
            // Дія при натисканні на стрілку назад
            onBackPressed(); // або finish();
            return true;
        }

        return super.onOptionsItemSelected(item);

    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //if (resultCode != RESULT_OK)
        //    return;
        if (requestCode == REQUEST_PICKER) {
            bitmap = null;
            if (resultCode == RESULT_OK) {
                try {
                    Uri uri = data.getData();
                    bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                } catch (IOException ex) {
                    //Snackbar.make(title, "Error", Snackbar.LENGTH_SHORT).show();
                }
            }
            image.setImageBitmap(bitmap);
        }
    }

}
