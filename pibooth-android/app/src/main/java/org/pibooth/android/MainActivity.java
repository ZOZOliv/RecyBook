package org.pibooth.android;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.pibooth.android.camera.BoothCamera;
import org.pibooth.android.camera.DslrCamera;
import org.pibooth.android.camera.HybridCamera;
import org.pibooth.android.camera.TabletCamera;
import org.pibooth.android.picture.ImageUtils;
import org.pibooth.android.picture.PictureFactory;
import org.pibooth.android.picture.PictureStore;
import org.pibooth.android.printer.BoothPrinter;
import org.pibooth.android.ptp.PtpConnection;
import org.pibooth.android.ptp.PtpDriver;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Écran du photomaton et machine à états (portage de pibooth/booth.py et
 * des plugins « core » : camera, picture, printer, view).
 *
 * <pre>
 * WAIT → CHOOSE → CHOSEN → PREVIEW → CAPTURE ─┬→ PREVIEW (prise suivante)
 *                                             └→ PROCESSING → PRINT → FINISH → WAIT
 * toute erreur → FAILSAFE → WAIT
 * </pre>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "pibooth";
    private static final String ACTION_USB_PERMISSION = "org.pibooth.android.USB_PERMISSION";
    private static final int REQ_PERMISSIONS = 1;
    private static final long CHOOSE_TIMEOUT_MS = 30_000;

    private enum State {WAIT, CHOOSE, CHOSEN, PREVIEW, CAPTURE, PROCESSING, PRINT, FINISH, FAILSAFE}

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private BoothConfig config;
    private Counters counters;
    private BoothPrinter printer;
    private UsbManager usbManager;

    // Vues
    private View root;
    private PreviewView tabletPreview;
    private ImageView dslrPreview;
    private TextView titleText, leftText, rightText, centerText, statusText;
    private View panels, leftPane, rightPane, center, flash;
    private ImageView leftImage, rightImage, centerImage;

    // Caméra
    private BoothCamera camera;
    private DslrCamera dslr;
    private boolean cameraSetupPending = false;
    private boolean cameraOpening = false;

    // Séquence en cours
    private State state = State.WAIT;
    private int[] captureChoices = {1};
    private int captureNbr = 1;
    private String captureDate;
    private final List<byte[]> rawCaptures = new ArrayList<>();
    private final List<Bitmap> captures = new ArrayList<>();
    private int captureRequested = 0;

    // Dernière photo
    private Bitmap previousPicture;
    private PictureStore.Saved previousSaved;
    private Bitmap secondPreviousPicture;
    private long previousPictureTime;

    private boolean returningFromSettings = false;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        config = new BoothConfig(this);
        counters = new Counters(this);
        printer = new BoothPrinter(this, config, counters);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        root = findViewById(R.id.root);
        tabletPreview = findViewById(R.id.tablet_preview);
        dslrPreview = findViewById(R.id.dslr_preview);
        titleText = findViewById(R.id.title_text);
        panels = findViewById(R.id.panels);
        leftPane = findViewById(R.id.left_pane);
        rightPane = findViewById(R.id.right_pane);
        leftImage = findViewById(R.id.left_image);
        rightImage = findViewById(R.id.right_image);
        leftText = findViewById(R.id.left_text);
        rightText = findViewById(R.id.right_text);
        center = findViewById(R.id.center);
        centerImage = findViewById(R.id.center_image);
        centerText = findViewById(R.id.center_text);
        flash = findViewById(R.id.flash);
        statusText = findViewById(R.id.status_text);

        root.setOnClickListener(v -> onTouchAnywhere());
        center.setOnClickListener(v -> onTouchAnywhere());
        leftPane.setOnClickListener(v -> onPaneTouched(true));
        rightPane.setOnClickListener(v -> onPaneTouched(false));
        View settings = findViewById(R.id.settings_button);
        settings.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        settings.setOnClickListener(v -> Toast.makeText(this, R.string.settings_hint, Toast.LENGTH_SHORT).show());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        applyStyle();
        if (requestMissingPermissions()) {
            setupCamera();
        }
        enterState(State.WAIT);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Lancement via le branchement d'un appareil (filtre USB du manifeste)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && dslr == null) {
            requestCameraSetup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (returningFromSettings) {
            returningFromSettings = false;
            applyStyle();
            closeCamera();
            setupCamera();
            enterState(State.WAIT);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        closeCamera();
        printer.shutdown();
        worker.shutdown();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void applyStyle() {
        int bg = config.windowBackground();
        int fg = config.windowTextColor();
        Typeface font = ImageUtils.font(this, config.windowFont());
        root.setBackgroundColor(bg);
        for (TextView t : new TextView[]{titleText, leftText, rightText, centerText, statusText}) {
            t.setTextColor(fg);
            if (t != statusText) t.setTypeface(font);
        }
        captureChoices = config.captureChoices();
    }

    // ------------------------------------------------------------------ permissions

    private boolean requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (missing.isEmpty()) return true;
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) setupCamera();
    }

    // ------------------------------------------------------------------ caméra

    private UsbDevice findUsbCamera() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (PtpConnection.isPtpDevice(d)) return d;
        }
        return null;
    }

    private void requestCameraSetup() {
        if (state == State.WAIT) {
            closeCamera();
            setupCamera();
        } else {
            cameraSetupPending = true;
        }
    }

    /** Choix de la caméra selon le mode configuré (équivalent de camera.find_camera()). */
    private void setupCamera() {
        if (cameraOpening) return;
        String mode = config.cameraMode();
        UsbDevice device = "tablet".equals(mode) ? null : findUsbCamera();

        if (device == null) {
            if ("dslr".equals(mode) || "hybrid".equals(mode)) {
                setStatus(getString(R.string.status_no_dslr));
                return;
            }
            openTabletCamera(null);
            return;
        }
        if (!usbManager.hasPermission(device)) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags));
            setStatus(getString(R.string.status_usb_permission));
            return;
        }
        openDslr(device);
    }

    private void openDslr(UsbDevice device) {
        cameraOpening = true;
        setStatus(getString(R.string.status_connecting, device.getProductName()));
        PtpDriver.Options opts = new PtpDriver.Options();
        opts.autofocus = config.autofocus();
        opts.canonCaptureToHost = config.canonCaptureToHost();
        DslrCamera.open(usbManager, device, opts, config.deleteInternalMemory(), config.rotation(),
                config.previewFlip(), new BoothCamera.OpenCallback() {
                    @Override
                    public void onOpened(BoothCamera cam) {
                        cameraOpening = false;
                        dslr = (DslrCamera) cam;
                        String mode = config.cameraMode();
                        boolean needTabletPreview = "hybrid".equals(mode)
                                || ("auto".equals(mode) && !dslr.supportsLiveView());
                        if (needTabletPreview && hasCameraPermission()) {
                            openTabletCamera(dslr);
                        } else {
                            useCamera(dslr);
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        cameraOpening = false;
                        Toast.makeText(MainActivity.this, getString(R.string.error_dslr, error.getMessage()),
                                Toast.LENGTH_LONG).show();
                        if ("auto".equals(config.cameraMode())) openTabletCamera(null);
                        else setStatus(getString(R.string.error_dslr, error.getMessage()));
                    }
                });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ouvre la caméra de la tablette, seule ou comme aperçu d'un reflex (mode hybride). */
    private void openTabletCamera(DslrCamera captureCamera) {
        if (!hasCameraPermission()) {
            if (captureCamera != null) useCamera(captureCamera);
            else setStatus(getString(R.string.status_no_camera));
            return;
        }
        cameraOpening = true;
        TabletCamera.open(this, this, tabletPreview, "front".equals(config.tabletLens()),
                new BoothCamera.OpenCallback() {
                    @Override
                    public void onOpened(BoothCamera cam) {
                        cameraOpening = false;
                        cam.stopPreview();
                        useCamera(captureCamera != null ? new HybridCamera(cam, captureCamera) : cam);
                    }

                    @Override
                    public void onError(Exception error) {
                        cameraOpening = false;
                        if (captureCamera != null) useCamera(captureCamera);
                        else setStatus(getString(R.string.status_no_camera));
                    }
                });
    }

    private void useCamera(BoothCamera cam) {
        camera = cam;
        setStatus(cam.getName());
        Log.i(TAG, "Caméra utilisée : " + cam.getName());
    }

    private void closeCamera() {
        if (camera != null) {
            camera.close();
        } else if (dslr != null) {
            dslr.close();
        }
        camera = null;
        dslr = null;
        dslrPreview.setVisibility(View.GONE);
        tabletPreview.setVisibility(View.GONE);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    closeCamera();
                    openDslr(device);
                } else {
                    setStatus(getString(R.string.status_usb_denied));
                    if ("auto".equals(config.cameraMode())) openTabletCamera(null);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (device != null && PtpConnection.isPtpDevice(device) && dslr == null) {
                    requestCameraSetup();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (dslr != null && device != null && dslr.getDevice() != null
                        && device.getDeviceName().equals(dslr.getDevice().getDeviceName())) {
                    Toast.makeText(MainActivity.this, R.string.status_dslr_detached, Toast.LENGTH_LONG).show();
                    closeCamera();
                    if (state != State.WAIT) enterState(State.FAILSAFE);
                    else setupCamera();
                }
            }
        }
    };

    // ------------------------------------------------------------------ entrées

    private void onTouchAnywhere() {
        if (state == State.WAIT) startSequence();
    }

    private void onPaneTouched(boolean left) {
        switch (state) {
            case WAIT:
                if (left && previousPicture != null && printer.canPrint()) printPrevious();
                else startSequence();
                break;
            case CHOOSE:
                choose(captureChoices[left ? 0 : Math.min(1, captureChoices.length - 1)]);
                break;
            case PRINT:
                if (left) forgetPicture();
                else printFromPrintState();
                break;
            default:
                break;
        }
    }

    /** Bouton « photo » physique (clavier, télécommande Bluetooth, volume +). */
    private void onCaptureButton() {
        switch (state) {
            case WAIT:
                startSequence();
                break;
            case CHOOSE:
                choose(captureChoices[0]);
                break;
            case PRINT:
                forgetPicture();
                break;
            default:
                break;
        }
    }

    /** Bouton « impression » physique (touche P, volume -). */
    private void onPrintButton() {
        switch (state) {
            case WAIT:
                if (previousPicture != null && printer.canPrint()) printPrevious();
                break;
            case CHOOSE:
                choose(captureChoices[Math.min(1, captureChoices.length - 1)]);
                break;
            case PRINT:
                printFromPrintState();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                onCaptureButton();
                return true;
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                onPrintButton();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                openSettings();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Mode borne : le bouton retour ne quitte pas l'application (réglages → Quitter).
        if (state != State.WAIT) enterState(State.WAIT);
    }

    // ------------------------------------------------------------------ états

    private void enterState(State next) {
        handler.removeCallbacksAndMessages(null);
        state = next;
        Log.d(TAG, "État : " + next);
        switch (next) {
            case WAIT:
                enterWait();
                break;
            case CHOOSE:
                enterChoose();
                break;
            case CHOSEN:
                enterChosen();
                break;
            case PREVIEW:
                enterPreview();
                break;
            case CAPTURE:
                enterCapture();
                break;
            case PROCESSING:
                enterProcessing();
                break;
            case PRINT:
                enterPrint();
                break;
            case FINISH:
                enterFinish();
                break;
            case FAILSAFE:
                enterFailsafe();
                break;
        }
    }

    private void enterWait() {
        stopPreview();
        resetSequence();
        if (cameraSetupPending) {
            cameraSetupPending = false;
            closeCamera();
            setupCamera();
        }
        captureChoices = config.captureChoices();
        statusText.setVisibility(View.VISIBLE);

        int delay = config.waitPictureDelay();
        if (previousPicture != null && delay >= 0
                && System.currentTimeMillis() - previousPictureTime >= delay * 1000L) {
            previousPicture = null; // wait_picture_delay écoulé : on masque la photo
        }

        if (previousPicture != null) {
            boolean canPrint = printer.canPrint() && counters.remainingDuplicates() > 0;
            showPanels(null,
                    previousPicture, false, canPrint ? getString(R.string.intro_print) : "",
                    "camera.png", getString(R.string.intro));
            if (delay > 0) {
                long remaining = delay * 1000L - (System.currentTimeMillis() - previousPictureTime);
                handler.postDelayed(() -> {
                    if (state == State.WAIT) enterState(State.WAIT);
                }, Math.max(0, remaining));
            }
        } else {
            showCenter(null, "camera.png", getString(R.string.intro));
        }
        if (printer.isEnabled() && printer.isQuotaReached()) {
            setStatus(getString(R.string.printer_check_paper));
        }
    }

    private void startSequence() {
        if (camera == null) {
            Toast.makeText(this, cameraOpening ? R.string.status_camera_opening : R.string.status_no_camera,
                    Toast.LENGTH_SHORT).show();
            if (!cameraOpening) setupCamera();
            return;
        }
        if (captureChoices.length > 1) {
            enterState(State.CHOOSE);
        } else {
            captureNbr = captureChoices[0];
            enterState(State.CHOSEN);
        }
    }

    private void enterChoose() {
        statusText.setVisibility(View.GONE);
        int a = captureChoices[0], b = captureChoices[1];
        showPanels(getString(R.string.choose),
                ImageUtils.loadAsset(this, "layout" + a + ".png"), true, nPhotos(a),
                "layout" + b + ".png", nPhotos(b));
        handler.postDelayed(() -> enterState(State.WAIT), CHOOSE_TIMEOUT_MS);
    }

    private String nPhotos(int n) {
        return getResources().getQuantityString(R.plurals.n_photos, n, n);
    }

    private void choose(int nbr) {
        captureNbr = nbr;
        enterState(State.CHOSEN);
    }

    private void enterChosen() {
        statusText.setVisibility(View.GONE);
        int delay = config.chosenDelay();
        if (delay <= 0) {
            enterState(State.PREVIEW);
            return;
        }
        showCenter(null, "layout" + captureNbr + ".png", getString(R.string.chosen));
        handler.postDelayed(() -> enterState(State.PREVIEW), delay * 1000L);
    }

    private void enterPreview() {
        statusText.setVisibility(View.GONE);
        if (captureDate == null) {
            captureDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
        }
        boolean live = startPreview();
        int seconds = config.previewDelay();
        String counter = config.previewCountdown() ? String.valueOf(seconds) : "";
        showCenter(null, live ? null : "camera.png", counter);
        if (live) hideCenterBackground();
        countdown(seconds);
    }

    private void countdown(int remaining) {
        if (state != State.PREVIEW) return;
        if (remaining <= 0) {
            enterState(State.CAPTURE);
            return;
        }
        if (config.previewCountdown()) centerText.setText(String.valueOf(remaining));
        handler.postDelayed(() -> countdown(remaining - 1), 1000);
    }

    private void enterCapture() {
        if (camera == null) {
            enterState(State.FAILSAFE);
            return;
        }
        centerText.setText(R.string.smile);
        if (config.previewStopOnCapture()) stopPreview();
        if (config.flash()) startFlash();
        captureRequested++;
        final int expected = captureRequested;
        camera.capture(new BoothCamera.CaptureCallback() {
            @Override
            public void onCaptured(BoothCamera.Capture capture) {
                if (state != State.CAPTURE || expected != captureRequested) return;
                stopFlash();
                onCaptureDone(capture);
            }

            @Override
            public void onError(Exception error) {
                if (state != State.CAPTURE) return;
                stopFlash();
                Log.e(TAG, "Erreur de capture", error);
                Toast.makeText(MainActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                enterState(State.FAILSAFE);
            }
        });
    }

    private void onCaptureDone(BoothCamera.Capture capture) {
        rawCaptures.add(capture.jpeg);
        int index = rawCaptures.size() - 1;
        String effect = config.captureEffect();
        int maxDim = captureNbr == 1 ? 3000 : 2000;
        int rotation = (camera instanceof DslrCamera || camera instanceof HybridCamera) ? config.rotation() : 0;
        boolean flip = config.flip();
        // Décodage en arrière-plan pendant que la séquence continue
        worker.execute(() -> {
            try {
                Bitmap bmp = ImageUtils.decodeCapture(capture.jpeg, capture.extraRotation, rotation, flip, maxDim);
                bmp = ImageUtils.applyEffect(bmp, effect);
                synchronized (captures) {
                    while (captures.size() <= index) captures.add(null);
                    captures.set(index, bmp);
                }
            } catch (Exception e) {
                Log.e(TAG, "Décodage impossible", e);
            }
        });
        if (rawCaptures.size() < captureNbr) {
            enterState(State.PREVIEW);
        } else {
            enterState(State.PROCESSING);
        }
    }

    private void enterProcessing() {
        stopPreview();
        showCenter(null, "processing.png", getString(R.string.processing));
        secondPreviousPicture = previousPicture;
        final String date = captureDate;
        final List<byte[]> raws = new ArrayList<>(rawCaptures);
        worker.execute(() -> {
            try {
                List<Bitmap> imgs;
                synchronized (captures) {
                    imgs = new ArrayList<>(captures);
                }
                imgs.removeIf(b -> b == null);
                if (imgs.isEmpty()) throw new IllegalStateException("Aucune prise exploitable");

                PictureStore store = new PictureStore(this, config.directoryName());
                if (config.saveRawCaptures()) {
                    for (int i = 0; i < raws.size(); i++) store.saveRaw(raws.get(i), date, i);
                }
                Bitmap picture = buildPicture(imgs, date);
                PictureStore.Saved saved = store.saveFinal(picture, date);
                for (Bitmap b : imgs) b.recycle();
                handler.post(() -> onProcessingDone(picture, saved));
            } catch (Throwable e) {
                Log.e(TAG, "Montage impossible", e);
                handler.post(() -> {
                    Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                    enterState(State.FAILSAFE);
                });
            }
        });
    }

    /** Montage final (pibooth_setup_picture_factory). */
    private Bitmap buildPicture(List<Bitmap> imgs, String date) throws Exception {
        PictureFactory factory = PictureFactory.create(imgs, config.orientation(), 600);
        factory.setMargin(config.marginThick());
        factory.setCropping(config.capturesCropping());
        factory.setBackgroundColor(config.pictureBackgroundColor());
        Bitmap bg = ImageUtils.loadUri(this, config.pictureBackgroundImage(), 3600);
        if (bg != null) factory.setBackgroundImage(bg);
        Bitmap overlay = ImageUtils.loadUri(this, config.pictureOverlay(), 3600);
        if (overlay != null) factory.setOverlay(overlay);

        String t1 = formatFooter(config.footerText1(), date);
        String t2 = formatFooter(config.footerText2(), date);
        if (!t1.isEmpty() || !t2.isEmpty()) {
            factory.addText(t1, ImageUtils.font(this, config.footerFont1()), config.footerColor(),
                    config.footerAlignment());
            factory.addText(t2, ImageUtils.font(this, config.footerFont2()), config.footerColor(),
                    config.footerAlignment());
        }
        return factory.build();
    }

    /** Variables de texte : {date} et {count} (comme texts_vars de pibooth). */
    private String formatFooter(String text, String date) throws Exception {
        if (text == null) return "";
        String out = text.trim();
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() >= 2) {
            out = out.substring(1, out.length() - 1);
        }
        Date d = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).parse(date);
        out = out.replace("{date}", new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(d));
        out = out.replace("{count}", String.valueOf(counters.taken() + 1));
        return out;
    }

    private void onProcessingDone(Bitmap picture, PictureStore.Saved saved) {
        if (state != State.PROCESSING) return;
        previousPicture = picture;
        previousSaved = saved;
        previousPictureTime = System.currentTimeMillis();
        counters.incTaken();
        counters.setRemainingDuplicates(config.maxDuplicates());

        int auto = config.autoPrint();
        if (auto != 0 && printer.canPrint()) {
            int pages = auto < 0 ? config.maxDuplicates() : Math.min(auto, config.maxDuplicates());
            printPicture(picture, pages);
            counters.setRemainingDuplicates(config.maxDuplicates() - pages);
            enterState(State.FINISH);
        } else if (printer.canPrint() && config.printerDelay() > 0) {
            enterState(State.PRINT);
        } else {
            enterState(State.FINISH);
        }
    }

    private void enterPrint() {
        showPanels(getString(R.string.print),
                previousPicture, false, getString(R.string.print_forget),
                "printer.png", getString(R.string.print_button));
        handler.postDelayed(() -> enterState(State.FINISH), config.printerDelay() * 1000L);
    }

    private void printFromPrintState() {
        if (previousPicture == null || !printer.canPrint()) return;
        printPicture(previousPicture, 1);
        counters.setRemainingDuplicates(counters.remainingDuplicates() - 1);
        enterState(State.FINISH);
    }

    private void forgetPicture() {
        if (previousSaved != null) {
            PictureStore store = new PictureStore(this, config.directoryName());
            final PictureStore.Saved s = previousSaved;
            worker.execute(() -> store.forget(s));
        }
        counters.incForgotten();
        // Revient à la photo précédente, non réimprimable
        previousPicture = secondPreviousPicture;
        previousSaved = null;
        counters.setRemainingDuplicates(0);
        enterState(State.FINISH);
    }

    private void printPrevious() {
        if (counters.remainingDuplicates() <= 0) {
            Toast.makeText(this, R.string.printer_max_duplicates, Toast.LENGTH_SHORT).show();
            return;
        }
        printPicture(previousPicture, 1);
        counters.setRemainingDuplicates(counters.remainingDuplicates() - 1);
        showCenter(null, "printer.png", getString(R.string.printing));
        handler.postDelayed(() -> enterState(State.WAIT), 3000);
    }

    private void printPicture(Bitmap picture, int pages) {
        printer.print(picture, pages, new BoothPrinter.Callback() {
            @Override
            public void onPrinted(int n) {
                Log.i(TAG, n + " page(s) envoyée(s) à l'imprimante");
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(MainActivity.this, getString(R.string.printer_error, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void enterFinish() {
        int delay = config.finishPictureDelay();
        if (delay > 0 && previousPicture != null) {
            showCenter(null, null, getString(R.string.finished));
            centerImage.clearColorFilter();
            centerImage.setImageBitmap(previousPicture);
            centerImage.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> enterState(State.WAIT), delay * 1000L);
        } else {
            showPanels(null, ImageUtils.loadAsset(this, "finished_left.png"), true, "",
                    "finished_right.png", "");
            titleText.setVisibility(View.VISIBLE);
            titleText.setText(R.string.finished);
            handler.postDelayed(() -> enterState(State.WAIT), 1500);
        }
    }

    private void enterFailsafe() {
        stopPreview();
        stopFlash();
        resetSequence();
        showCenter(null, null, getString(R.string.oops));
        handler.postDelayed(() -> enterState(State.WAIT), 2500);
    }

    private void resetSequence() {
        captureDate = null;
        rawCaptures.clear();
        synchronized (captures) {
            captures.clear();
        }
        captureRequested++;
    }

    // ------------------------------------------------------------------ aperçu

    /** Démarre l'aperçu ; renvoie faux si aucune visée n'est disponible. */
    private boolean startPreview() {
        if (camera == null) return false;
        if (camera.usesPreviewSink()) {
            if (dslr == null || !dslr.supportsLiveView()) return false;
            dslrPreview.setVisibility(View.VISIBLE);
            camera.startPreview(frame -> dslrPreview.setImageBitmap(frame));
        } else {
            camera.startPreview(null);
        }
        return true;
    }

    private void stopPreview() {
        if (camera != null) camera.stopPreview();
        dslrPreview.setVisibility(View.GONE);
        dslrPreview.setImageDrawable(null);
        tabletPreview.setVisibility(View.GONE);
    }

    private void hideCenterBackground() {
        centerImage.setVisibility(View.INVISIBLE);
    }

    private void startFlash() {
        flash.setVisibility(View.VISIBLE);
        flash.setAlpha(0f);
        flash.animate().alpha(1f).setDuration(120).withEndAction(() ->
                flash.animate().alpha(0.15f).setDuration(250).start()).start();
    }

    private void stopFlash() {
        flash.animate().cancel();
        flash.animate().alpha(0f).setDuration(150).withEndAction(() -> flash.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------------ affichage

    private void showCenter(String title, String asset, String text) {
        panels.setVisibility(View.GONE);
        center.setVisibility(View.VISIBLE);
        setTitle(title);
        setPicto(centerImage, asset);
        centerText.setText(text);
    }

    private void showPanels(String title, Bitmap leftBmp, boolean leftIsPicto, String left,
                            String rightAsset, String right) {
        center.setVisibility(View.GONE);
        panels.setVisibility(View.VISIBLE);
        setTitle(title);
        setBitmap(leftImage, leftBmp, leftIsPicto);
        leftText.setText(left);
        setPicto(rightImage, rightAsset);
        rightText.setText(right);
    }

    private void setTitle(String title) {
        if (title == null) {
            titleText.setVisibility(View.GONE);
        } else {
            titleText.setVisibility(View.VISIBLE);
            titleText.setText(title);
        }
    }

    private void setPicto(ImageView view, String asset) {
        if (asset == null) {
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            return;
        }
        setBitmap(view, ImageUtils.loadAsset(this, asset), true);
    }

    private void setBitmap(ImageView view, Bitmap bmp, boolean picto) {
        view.setVisibility(View.VISIBLE);
        view.setImageBitmap(bmp);
        if (picto) view.setColorFilter(ImageUtils.colorizeFilter(config.windowTextColor(), config.windowBackground()));
        else view.clearColorFilter();
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    // ------------------------------------------------------------------ réglages

    private void openSettings() {
        String pin = config.settingsPin();
        if (pin.isEmpty()) {
            launchSettings();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_pin_prompt)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (pin.equals(input.getText().toString().trim())) launchSettings();
                    else Toast.makeText(this, R.string.settings_pin_wrong, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchSettings() {
        handler.removeCallbacksAndMessages(null);
        stopPreview();
        returningFromSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
