package org.pibooth.android.picture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Assemble 1 à 4 prises sur une page (portage de pibooth/pictures/factory.py
 * avec le Canvas Android à la place de PIL/OpenCV).
 *
 * <pre>
 *   portrait :  1 : [1]     2 : [1]/[2]     3 : [1]/[2]/[3]     4 : [1][2]/[3][4]
 *   paysage  :  1 : [1]     2 : [1][2]      3 : [1][2][3]       4 : [1][2]/[3][4]
 * </pre>
 */
public final class PictureFactory {

    public static final String AUTO = "auto";
    public static final String PORTRAIT = "portrait";
    public static final String LANDSCAPE = "landscape";

    private static final class Text {
        final String text;
        final Typeface font;
        final int color;
        final String align;

        Text(String text, Typeface font, int color, String align) {
            this.text = text;
            this.font = font;
            this.color = color;
            this.align = align;
        }
    }

    private final int width;
    private final int height;
    private final boolean portrait;
    private final List<Bitmap> images;
    private final List<Text> texts = new ArrayList<>();
    private int textsHeight = 0;
    private int margin = 100;
    private int marginText = 100;
    private boolean crop = false;
    private boolean outlines = false;
    private int backgroundColor = Color.WHITE;
    private Bitmap backgroundImage;
    private Bitmap overlayImage;

    public PictureFactory(int width, int height, List<Bitmap> images) {
        if (images.isEmpty() || images.size() > 4) {
            throw new IllegalArgumentException("1 à 4 images peuvent être assemblées");
        }
        this.width = width;
        this.height = height;
        this.portrait = width < height;
        this.images = images;
    }

    /** Orientation la plus adaptée selon la première prise (get_best_orientation). */
    public static String bestOrientation(List<Bitmap> captures) {
        Bitmap first = captures.get(0);
        boolean isPortrait = first.getWidth() < first.getHeight();
        int n = captures.size();
        if (n == 1 || n == 4) return isPortrait ? PORTRAIT : LANDSCAPE;
        return isPortrait ? LANDSCAPE : PORTRAIT;
    }

    /** Fabrique pour un papier 4×6 pouces (get_picture_factory). */
    public static PictureFactory create(List<Bitmap> captures, String orientation, int dpi) {
        if (orientation == null || AUTO.equals(orientation)) orientation = bestOrientation(captures);
        int w = 4 * dpi;
        int h = 6 * dpi;
        if (LANDSCAPE.equals(orientation)) {
            int t = w;
            w = h;
            h = t;
        }
        return new PictureFactory(w, h, captures);
    }

    public PictureFactory setMargin(int margin) {
        this.margin = margin;
        this.marginText = margin;
        return this;
    }

    public PictureFactory setCropping(boolean crop) {
        this.crop = crop;
        return this;
    }

    public PictureFactory setOutlines(boolean outlines) {
        this.outlines = outlines;
        return this;
    }

    public PictureFactory setBackgroundColor(int color) {
        this.backgroundColor = color;
        return this;
    }

    public PictureFactory setBackgroundImage(Bitmap image) {
        this.backgroundImage = image;
        return this;
    }

    public PictureFactory setOverlay(Bitmap image) {
        this.overlayImage = image;
        return this;
    }

    public PictureFactory addText(String text, Typeface font, int color, String align) {
        texts.add(new Text(text, font, color, align));
        textsHeight = portrait ? height / 6 : height / 8;
        return this;
    }

    // ------------------------------------------------------------------ géométrie

    private List<Rect> imageRects() {
        List<Rect> rects = new ArrayList<>();
        int n = images.size();
        int x = margin, y = margin;
        int totalW = width - 2 * margin;
        int totalH = height - textsHeight - 2 * margin;
        int w, h;
        if (n == 1) {
            w = totalW;
            h = totalH;
        } else if (n < 4) {
            if (portrait) {
                w = totalW;
                h = (totalH - (n - 1) * margin) / n;
            } else {
                w = (totalW - (n - 1) * margin) / n;
                h = totalH;
            }
        } else {
            w = (totalW - margin) / 2;
            h = (totalH - margin) / 2;
        }
        rects.add(new Rect(x, y, x + w, y + h));
        if (n == 2 || n == 3) {
            for (int i = 1; i < n; i++) {
                if (portrait) y += h + margin;
                else x += w + margin;
                rects.add(new Rect(x, y, x + w, y + h));
            }
        } else if (n == 4) {
            x += w + margin;
            rects.add(new Rect(x, y, x + w, y + h));
            y += h + margin;
            x = margin;
            rects.add(new Rect(x, y, x + w, y + h));
            x += w + margin;
            rects.add(new Rect(x, y, x + w, y + h));
        }
        return rects;
    }

    private List<Rect> textRects() {
        List<Rect> rects = new ArrayList<>();
        int interline = 20;
        int x = marginText;
        int y = height - textsHeight;
        int totalW = width - 2 * marginText;
        int totalH = textsHeight - marginText;
        int n = texts.size();
        if (portrait) {
            int th = (totalH - interline * (n - 1)) / (n + 1);
            for (int i = 0; i < n; i++) {
                if (i == 0) {
                    rects.add(new Rect(x, y, x + totalW, y + 2 * th));
                } else {
                    y += interline + (i == 1 ? 2 * th : th);
                    rects.add(new Rect(x, y, x + totalW, y + th));
                }
            }
        } else {
            int tw = (totalW - interline * (n - 1)) / n;
            int th = totalH / 2;
            for (int i = 0; i < n; i++) {
                if (i == 0) {
                    rects.add(new Rect(x, y, x + tw, y + 2 * th));
                } else {
                    x += interline + tw;
                    int ty = y + (totalH - th) / 2;
                    rects.add(new Rect(x, ty, x + tw, ty + th));
                }
            }
        }
        return rects;
    }

    // ------------------------------------------------------------------ rendu

    public Bitmap build() {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        // Fond
        canvas.drawColor(backgroundColor);
        if (backgroundImage != null) {
            Bitmap bg = ImageUtils.centerCrop(backgroundImage, width, height);
            canvas.drawBitmap(bg, 0, 0, bmpPaint);
            bg.recycle();
        }

        // Matrice des prises
        List<Rect> rects = imageRects();
        int n = images.size();
        for (int i = 0; i < n; i++) {
            Bitmap src = images.get(i);
            Rect r = rects.get(i);
            int maxW = r.width(), maxH = r.height();
            Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
            int dw, dh;
            if (crop) {
                // Recadre la source au ratio de la cellule
                float target = (float) maxW / maxH;
                float ratio = (float) src.getWidth() / src.getHeight();
                if (ratio > target) {
                    int cw = Math.round(src.getHeight() * target);
                    int off = (src.getWidth() - cw) / 2;
                    srcRect.set(off, 0, off + cw, src.getHeight());
                } else {
                    int ch = Math.round(src.getWidth() / target);
                    int off = (src.getHeight() - ch) / 2;
                    srcRect.set(0, off, src.getWidth(), off + ch);
                }
                dw = maxW;
                dh = maxH;
            } else {
                float scale = Math.min((float) maxW / src.getWidth(), (float) maxH / src.getHeight());
                dw = Math.round(src.getWidth() * scale);
                dh = Math.round(src.getHeight() * scale);
            }
            // Même répartition des marges que pibooth (bords / centre)
            int px, py;
            if (n < 4) {
                px = r.left + (maxW - dw) / 2;
                py = r.top + (maxH - dh) / 2;
            } else if (i == 0) {
                px = r.left + (maxW - dw) * 2 / 3;
                py = r.top + (maxH - dh) * 2 / 3;
            } else if (i == 1) {
                px = r.left + (maxW - dw) / 3;
                py = r.top + (maxH - dh) * 2 / 3;
            } else if (i == 2) {
                px = r.left + (maxW - dw) * 2 / 3;
                py = r.top + (maxH - dh) / 3;
            } else {
                px = r.left + (maxW - dw) / 3;
                py = r.top + (maxH - dh) / 3;
            }
            canvas.drawBitmap(src, srcRect, new Rect(px, py, px + dw, py + dh), bmpPaint);
        }

        // Textes
        if (!texts.isEmpty()) {
            List<Rect> trs = textRects();
            for (int i = 0; i < texts.size(); i++) {
                Text t = texts.get(i);
                if (t.text == null || t.text.isEmpty()) continue;
                drawFittedText(canvas, t.text, t.font, t.color, t.align, new RectF(trs.get(i)));
            }
        }

        // Superposition (cadre PNG)
        if (overlayImage != null) {
            canvas.drawBitmap(overlayImage, null, new Rect(0, 0, width, height), bmpPaint);
        }

        if (outlines) {
            Paint red = new Paint();
            red.setStyle(Paint.Style.STROKE);
            red.setColor(Color.RED);
            red.setStrokeWidth(3);
            for (Rect r : rects) canvas.drawRect(r, red);
            if (!texts.isEmpty()) for (Rect r : textRects()) canvas.drawRect(r, red);
        }
        return out;
    }

    /** Dessine un texte d'une ligne avec la plus grande taille qui tient dans le rectangle. */
    public static void drawFittedText(Canvas canvas, String text, Typeface font, int color,
                                      String align, RectF box) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(font);
        p.setColor(color);
        float lo = 4, hi = Math.max(8, box.height() * 1.5f);
        for (int it = 0; it < 20; it++) {
            float mid = (lo + hi) / 2;
            p.setTextSize(mid);
            Paint.FontMetrics fm = p.getFontMetrics();
            if (p.measureText(text) <= box.width() && (fm.descent - fm.ascent) <= box.height()) lo = mid;
            else hi = mid;
        }
        p.setTextSize(lo);
        Paint.FontMetrics fm = p.getFontMetrics();
        float tw = p.measureText(text);
        float x = box.left;
        if ("center".equals(align)) x += (box.width() - tw) / 2;
        else if ("right".equals(align)) x += box.width() - tw;
        float y = box.top + (box.height() - (fm.descent - fm.ascent)) / 2 - fm.ascent;
        canvas.drawText(text, x, y, p);
    }
}
