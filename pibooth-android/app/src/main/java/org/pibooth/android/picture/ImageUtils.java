package org.pibooth.android.picture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Fonctions d'image (équivalent de pibooth/pictures/__init__.py et sizing.py). */
public final class ImageUtils {

    private static final String TAG = "ImageUtils";
    private static final Map<String, Typeface> FONTS = new HashMap<>();

    /** Effets disponibles pour les prises (les filtres PIL de pibooth n'existent pas sous Android). */
    public static final String[] EFFECTS = {"none", "grayscale", "sepia", "vintage", "invert"};

    private ImageUtils() {
    }

    /**
     * Décode une prise JPEG en appliquant l'orientation EXIF, la rotation
     * supplémentaire, la rotation configurée et le miroir. L'image est
     * sous-échantillonnée pour ne pas dépasser {@code maxDim} pixels (mémoire).
     */
    public static Bitmap decodeCapture(byte[] jpeg, int extraRotation, int configRotation,
                                       boolean flip, int maxDim) throws IOException {
        int exifRotation = 0;
        boolean exifFlip = false;
        try (InputStream in = new ByteArrayInputStream(jpeg)) {
            ExifInterface exif = new ExifInterface(in);
            exifRotation = exif.getRotationDegrees();
            exifFlip = exif.isFlipped();
        } catch (Exception e) {
            Log.w(TAG, "EXIF illisible : " + e.getMessage());
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0) throw new IOException("Image JPEG invalide");

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (opts.inSampleSize * 2) >= maxDim) opts.inSampleSize *= 2;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) throw new IOException("Décodage JPEG impossible");

        int rotation = ((exifRotation + extraRotation + configRotation) % 360 + 360) % 360;
        boolean mirror = flip ^ exifFlip;
        float scale = Math.min(1f, (float) maxDim / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (rotation == 0 && !mirror && scale >= 1f) return bmp;

        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        m.postRotate(rotation);
        if (mirror) m.postScale(-1, 1);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    /** Applique un effet de couleur à une prise. */
    public static Bitmap applyEffect(Bitmap src, String effect) {
        ColorMatrix cm = new ColorMatrix();
        switch (effect == null ? "none" : effect) {
            case "grayscale":
                cm.setSaturation(0);
                break;
            case "sepia": {
                cm.setSaturation(0);
                ColorMatrix tint = new ColorMatrix();
                tint.setScale(1f, 0.95f, 0.82f, 1f);
                cm.postConcat(tint);
                break;
            }
            case "vintage": {
                cm.setSaturation(0.6f);
                ColorMatrix warm = new ColorMatrix(new float[]{
                        1.05f, 0, 0, 0, 12,
                        0, 1.0f, 0, 0, 6,
                        0, 0, 0.85f, 0, -6,
                        0, 0, 0, 1, 0});
                cm.postConcat(warm);
                break;
            }
            case "invert":
                cm.set(new float[]{
                        -1, 0, 0, 0, 255,
                        0, -1, 0, 0, 255,
                        0, 0, -1, 0, 255,
                        0, 0, 0, 1, 0});
                break;
            default:
                return src;
        }
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        src.recycle();
        return out;
    }

    /**
     * Filtre recolorant un picto blanc/noir : blanc → {@code color},
     * noir → {@code bgColor} (équivalent de colorize_pil_image).
     */
    public static ColorMatrixColorFilter colorizeFilter(int color, int bgColor) {
        float[] l = {0.299f, 0.587f, 0.114f};
        float[] m = new float[20];
        int[] fg = {Color.red(color), Color.green(color), Color.blue(color)};
        int[] bg = {Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)};
        for (int c = 0; c < 3; c++) {
            float k = (fg[c] - bg[c]) / 255f;
            m[c * 5] = k * l[0];
            m[c * 5 + 1] = k * l[1];
            m[c * 5 + 2] = k * l[2];
            m[c * 5 + 4] = bg[c];
        }
        m[18] = 1f; // alpha conservé
        return new ColorMatrixColorFilter(m);
    }

    /** Charge une image des assets (pictos pibooth). */
    public static Bitmap loadAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open("images/" + name)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.w(TAG, "Image absente : " + name);
            return null;
        }
    }

    /** Charge une image choisie par l'utilisateur (URI SAF), réduite à maxDim. */
    public static Bitmap loadUri(Context context, String uri, int maxDim) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            Uri u = Uri.parse(uri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= maxDim) opts.inSampleSize *= 2;
            try (InputStream in = context.getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            Log.w(TAG, "Image illisible : " + uri, e);
            return null;
        }
    }

    /** Police embarquée (Amatic, DancingScript, Roboto, Monoid) ou police système. */
    public static synchronized Typeface font(Context context, String name) {
        if (name == null || name.isEmpty()) return Typeface.DEFAULT_BOLD;
        Typeface tf = FONTS.get(name);
        if (tf != null) return tf;
        try {
            String file = name.endsWith(".ttf") ? name : name + ".ttf";
            tf = Typeface.createFromAsset(context.getAssets(), "fonts/" + file);
        } catch (Exception e) {
            tf = Typeface.create(name, Typeface.NORMAL);
        }
        FONTS.put(name, tf);
        return tf;
    }

    /** Recadre (centre) pour remplir exactement w×h. */
    public static Bitmap centerCrop(Bitmap src, int w, int h) {
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
