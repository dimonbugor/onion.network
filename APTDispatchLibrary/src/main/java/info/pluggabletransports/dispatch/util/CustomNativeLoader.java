package info.pluggabletransports.dispatch.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CustomNativeLoader {

    private final static String TAG = "TorNativeLoader";

    private static boolean loadFromZip(Context context, File destLocalFile, String arch, String lib) {


        ZipFile zipFile = null;
        InputStream stream = null;

        try {
            zipFile = new ZipFile(context.getApplicationInfo().sourceDir);
            ZipEntry entry = zipFile.getEntry("lib/" + arch + "/" + lib + ".so");
            if (entry == null) {
                throw new Exception("Unable to find file in apk:" + "lib/" + arch + "/" + lib + ".so");
            }

            //how we wrap this in another stream because the native .so is zipped itself
            stream = zipFile.getInputStream(entry);

            OutputStream out = new FileOutputStream(destLocalFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = stream.read(buf)) > 0) {
                Thread.yield();
                out.write(buf, 0, len);
            }
            out.close();

            if (Build.VERSION.SDK_INT >= 9) {
                destLocalFile.setReadable(true, false);
                destLocalFile.setExecutable(true, false);
                destLocalFile.setWritable(true);
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                }
            }
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                }
            }
        }
        return false;
    }

    public static synchronized File initNativeLibs(Context context, File destLocalFile, String lib) {

        try {
            String folder = Build.CPU_ABI;

            String javaArch = System.getProperty("os.arch");
            if (javaArch != null && javaArch.contains("686")) {
                folder = "x86";
            }


            if (loadFromZip(context, destLocalFile, folder, lib)) {
                return destLocalFile;
            }

        } catch (Throwable e) {
            Log.e(TAG, e.getMessage(),e);
        }


        return null;
    }
}

