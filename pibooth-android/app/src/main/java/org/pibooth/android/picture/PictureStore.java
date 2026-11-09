package org.pibooth.android.picture;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Enregistrement des photos dans la galerie, comme pibooth :
 * <ul>
 *   <li>Pictures/&lt;dossier&gt;/AAAA-MM-JJ-hh-mm-ss_pibooth.jpg (montage final)</li>
 *   <li>Pictures/&lt;dossier&gt;/raw/AAAA-MM-JJ-hh-mm-ss/pibooth000.jpg (prises brutes)</li>
 *   <li>Pictures/&lt;dossier&gt;/forget/ (photos « oubliées »)</li>
 * </ul>
 */
public final class PictureStore {

    private static final String TAG = "PictureStore";

    private final Context context;
    private final String directory;

    /** Référence d'une photo enregistrée (URI MediaStore ou fichier). */
    public static final class Saved {
        public final Uri uri;
        public final File file;
        public final String name;

        Saved(Uri uri, File file, String name) {
            this.uri = uri;
            this.file = file;
            this.name = name;
        }
    }

    public PictureStore(Context context, String directory) {
        this.context = context.getApplicationContext();
        this.directory = directory;
    }

    public Saved saveFinal(Bitmap picture, String date) throws IOException {
        String name = date + "_pibooth.jpg";
        return write(directory, name, out -> picture.compress(Bitmap.CompressFormat.JPEG, 92, out));
    }

    public void saveRaw(byte[] jpeg, String date, int index) throws IOException {
        String name = String.format("pibooth%03d.jpg", index);
        write(directory + "/raw/" + date, name, out -> {
            out.write(jpeg);
            return true;
        });
    }

    /** Déplace la photo dans le sous-dossier « forget ». */
    public void forget(Saved saved) {
        try {
            if (saved.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + directory + "/forget");
                context.getContentResolver().update(saved.uri, v, null, null);
            } else if (saved.file != null) {
                File dir = new File(saved.file.getParentFile(), "forget");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                saved.file.renameTo(new File(dir, saved.file.getName()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Déplacement vers forget impossible", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private interface Writer {
        boolean write(OutputStream out) throws IOException;
    }

    @SuppressWarnings("deprecation")
    private Saved write(String relativeDir, String name, Writer writer) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + relativeDir);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore a refusé " + name);
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null || !writer.write(out)) throw new IOException("Écriture impossible : " + name);
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return new Saved(uri, null, name);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    relativeDir);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Dossier impossible : " + dir);
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!writer.write(out)) throw new IOException("Écriture impossible : " + name);
            }
            android.media.MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                    null, null);
            return new Saved(null, file, name);
        }
    }
}
