

package onion.network.models;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

public class QR {

    public static Bitmap make(String txt) {

        QRCode qr;

        try {
            qr = Encoder.encode(txt, ErrorCorrectionLevel.M);
        } catch (Exception ex) {
            throw new Error(ex);
        }

        ByteMatrix mat = qr.getMatrix();
        int width = mat.getWidth();
        int height = mat.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = mat.get(x, y) != 0 ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() * 8, bitmap.getHeight() * 8, false);

        return bitmap;

    }

}
