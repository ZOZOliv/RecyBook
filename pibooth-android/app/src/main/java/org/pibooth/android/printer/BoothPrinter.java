package org.pibooth.android.printer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.print.PrintHelper;

import org.pibooth.android.BoothConfig;
import org.pibooth.android.Counters;
import org.pibooth.android.picture.PictureFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impression (équivalent de pibooth/printer.py, CUPS remplacé par IPP
 * direct ou par le service d'impression Android).
 */
public final class BoothPrinter {

    private static final String TAG = "BoothPrinter";

    public interface Callback {
        void onPrinted(int pages);

        void onError(Exception e);
    }

    private final Activity activity;
    private final BoothConfig config;
    private final Counters counters;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    public BoothPrinter(Activity activity, BoothConfig config, Counters counters) {
        this.activity = activity;
        this.config = config;
        this.counters = counters;
    }

    public boolean isEnabled() {
        String mode = config.printerMode();
        if ("ipp".equals(mode)) return !config.printerUrl().isEmpty();
        return "android".equals(mode);
    }

    /** Vrai si le quota de pages (max_pages) est atteint : papier / encre à vérifier. */
    public boolean isQuotaReached() {
        int max = config.maxPages();
        return max >= 0 && counters.printed() >= max;
    }

    public boolean canPrint() {
        return isEnabled() && !busy && !isQuotaReached();
    }

    public boolean isBusy() {
        return busy;
    }

    /** Imprime {@code copies} pages de la photo. */
    public void print(Bitmap picture, int copies, Callback callback) {
        if (!isEnabled() || busy) return;
        busy = true;
        final int pages = Math.max(1, copies);
        worker.execute(() -> {
            try {
                Bitmap page = buildPage(picture);
                if ("ipp".equals(config.printerMode())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    page.compress(Bitmap.CompressFormat.JPEG, 95, bos);
                    IppClient.printJpeg(config.printerUrl(), bos.toByteArray(), pages, config.printerMedia());
                    finish(callback, pages, null);
                } else {
                    main.post(() -> {
                        try {
                            PrintHelper helper = new PrintHelper(activity);
                            helper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
                            helper.setColorMode(PrintHelper.COLOR_MODE_COLOR);
                            helper.printBitmap("pibooth", page);
                            finish(callback, 1, null);
                        } catch (Exception e) {
                            finish(callback, 0, e);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Impression impossible", e);
                finish(callback, 0, e);
            }
        });
    }

    private void finish(Callback callback, int pages, Exception error) {
        main.post(() -> {
            busy = false;
            if (error != null) {
                callback.onError(error);
            } else {
                counters.addPrinted(pages);
                callback.onPrinted(pages);
            }
        });
    }

    /** Plusieurs exemplaires par page si pictures_per_page &gt; 1. */
    private Bitmap buildPage(Bitmap picture) {
        int n = config.picturesPerPage();
        if (n <= 1) return picture;
        List<Bitmap> copies = new ArrayList<>(Collections.nCopies(n, picture));
        String orientation = picture.getWidth() > picture.getHeight()
                ? PictureFactory.LANDSCAPE : PictureFactory.PORTRAIT;
        if (n == 2 || n == 3) {
            orientation = PictureFactory.LANDSCAPE.equals(orientation)
                    ? PictureFactory.PORTRAIT : PictureFactory.LANDSCAPE;
        }
        return PictureFactory.create(copies, orientation, 300).setMargin(20).build();
    }

    public void shutdown() {
        worker.shutdown();
    }
}
