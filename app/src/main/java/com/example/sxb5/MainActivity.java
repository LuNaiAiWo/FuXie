package com.example.sxb5;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Keep;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@SuppressWarnings({"SpellCheckingInspection", "unused"})
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_NAME = "ball_settings";
    private static final String KEY_BALL_BASE64 = "custom_ball_base64";
    private static final String DEFAULT_BALL = "default";

    private WindowManager windowManager;
    private WindowManager.LayoutParams webViewLayoutParams;
    private WindowManager.LayoutParams ballLayoutParams;

    private WebView myWebView;
    private AccessibleImageView nativeBall;

    private int screenHeight, ballSize, safetyPadding, lastBallY;

    private boolean isWebViewAttached = false;
    private boolean isBallAttached = false;
    private boolean isPageLoaded = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> pickImageLauncher;

    @SuppressLint("ViewConstructor")
    private static class AccessibleImageView extends AppCompatImageView {
        public AccessibleImageView(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    // ==========================================
    // 1. 生命周期与核心入口
    // ==========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        // 💡 稳固核心：直接绑定完美的 XML CenterCrop 背景，无论何时启动、何时返回，比例永远由系统锁死不变形
        getWindow().setBackgroundDrawableResource(R.drawable.bg_scaled_transition);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        initImagePicker();
        updateScreenSize();

        setupWindowLayoutParams();
        initWebView();
        initNativeBall();

        initFloatingWindow();
        minimizeToBall();

        moveTaskToBack(true);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        overridePendingTransition(0, 0);

        minimizeToBall();
        moveTaskToBack(true);

        if (myWebView != null && !isPageLoaded) {
            myWebView.loadUrl("file:///android_asset/sxb5.html");
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        handleScreenRotation();
    }

    @Override
    protected void onDestroy() {
        cleanUpWindow();
        super.onDestroy();
    }

    // ==========================================
    // 2. 初始化与配置模块
    // ==========================================

    private void updateScreenSize() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenHeight = metrics.heightPixels;
        safetyPadding = (int) (60 * metrics.density);
    }

    private void setupWindowLayoutParams() {
        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        ballSize = (int) (55 * metrics.density);
        lastBallY = (screenHeight - ballSize) / 2;

        webViewLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        webViewLayoutParams.gravity = Gravity.TOP | Gravity.START;
        webViewLayoutParams.x = 0;
        webViewLayoutParams.y = 0;
        webViewLayoutParams.alpha = 0.0f;
        webViewLayoutParams.windowAnimations = 0;

        ballLayoutParams = new WindowManager.LayoutParams(
                ballSize,
                ballSize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        ballLayoutParams.gravity = Gravity.TOP | Gravity.START;
        ballLayoutParams.x = 0;
        ballLayoutParams.y = lastBallY;
        ballLayoutParams.windowAnimations = 0;
    }

    private void initFloatingWindow() {
        try {
            if (!isWebViewAttached && myWebView != null) {
                windowManager.addView(myWebView, webViewLayoutParams);
                isWebViewAttached = true;
            }
            if (!isBallAttached && nativeBall != null) {
                windowManager.addView(nativeBall, ballLayoutParams);
                isBallAttached = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "常驻双视窗初始化挂载失败", e);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        myWebView = new WebView(this);
        myWebView.setBackgroundColor(Color.TRANSPARENT);

        // 🚀 核心优化：开启 WebView 硬件加速层提升 CSS Translate 渲染效能
        myWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setDatabaseEnabled(true);

        // 💡 关键重构：全面关闭 WebView 原生手势缩放支持，防止与前端双沙盒双指缩放逻辑打架导致画面严重抖动
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        myWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        myWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                syncTextureToWebView(getSavedBallBase64());

                // 延时 60ms 确保视窗完全布局展开后再通知前端测算左右平分宽度，防御 0 宽度破坏布局
                mainHandler.postDelayed(() -> executeJavaScript("if(window.resizeCanvas){window.resizeCanvas();}"), 60);
            }
        });

        myWebView.loadUrl("file:///android_asset/sxb5.html");
    }

    private void initNativeBall() {
        nativeBall = new AccessibleImageView(this);
        updateBallTexture(getSavedBallBase64());
        nativeBall.setOnTouchListener(new FloatingBallTouchListener());
    }

    // ==========================================
    // 3. 状态切换模块
    // ==========================================

    private void maximizeApp() {
        runOnUiThread(() -> {
            if (isBallAttached && nativeBall != null) {
                nativeBall.setVisibility(View.INVISIBLE);
            }

            if (isWebViewAttached && myWebView != null) {
                webViewLayoutParams.alpha = 1.0f;
                webViewLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                windowManager.updateViewLayout(myWebView, webViewLayoutParams);
                myWebView.setVisibility(View.VISIBLE);
            }

            // 💡 异步通知前端重绘并保证获取当前最新的分屏宽度
            executeJavaScript("if(window.onAppMaximized){window.onAppMaximized();}");
        });
    }

    private void minimizeToBall() {
        runOnUiThread(() -> {
            if (isWebViewAttached && myWebView != null) {
                webViewLayoutParams.alpha = 0.0f;
                webViewLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                windowManager.updateViewLayout(myWebView, webViewLayoutParams);
                myWebView.setVisibility(View.VISIBLE);
            }

            if (isBallAttached && nativeBall != null) {
                ballLayoutParams.x = 0;
                ballLayoutParams.y = lastBallY;
                windowManager.updateViewLayout(nativeBall, ballLayoutParams);
                nativeBall.setVisibility(View.VISIBLE);
            }
        });
    }

    private void handleScreenRotation() {
        updateScreenSize();
        if (isBallAttached && nativeBall != null && nativeBall.getVisibility() == View.VISIBLE) {
            int newMax = screenHeight - ballSize - safetyPadding;
            lastBallY = Math.max(safetyPadding, Math.min(lastBallY, newMax));
            ballLayoutParams.y = lastBallY;
            updateBallWindowLayout();
        }

        if (isWebViewAttached && myWebView != null && webViewLayoutParams.alpha == 1.0f) {
            // 屏幕旋转后，通知前端重新测算 width/2 重新切割左右两个独立 Canvas 画布
            executeJavaScript("if(window.resizeCanvas){window.resizeCanvas()}");
        }
    }

    private void updateBallWindowLayout() {
        if (isBallAttached && windowManager != null && nativeBall != null) {
            try {
                windowManager.updateViewLayout(nativeBall, ballLayoutParams);
            } catch (Exception e) {
                Log.e(TAG, "更新悬浮球布局失败", e);
            }
        }
    }

    // ==========================================
    // 4. 图片处理与数据持久化模块
    // ==========================================

    private void initImagePicker() {
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) handlePickedImageUri(uri);
                    }
                }
        );
    }

    private void triggerNativeImagePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/jpg"});
            pickImageLauncher.launch(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                pickImageLauncher.launch(intent);
            } catch (Exception ex) {
                Toast.makeText(this, "无法开启系统相册", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handlePickedImageUri(Uri uri) {
        if (isFinishing() || isDestroyed()) return;

        try {
            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null || !mimeType.matches("image/(jpeg|png|jpg)")) {
                Toast.makeText(this, "格式错误！仅限选择 JPG 或 PNG 图片", Toast.LENGTH_LONG).show();
                return;
            }

            int inSampleSize = calculateInSampleSize(uri);

            try (InputStream imageStream = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = inSampleSize;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeStream(imageStream, null, options);

                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(mimeType.contains("png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 70, baos);

                    String base64Data = "data:" + mimeType + ";base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    bitmap.recycle();

                    saveBallBase64(base64Data);
                    updateBallTexture(base64Data);
                    syncTextureToWebView(base64Data);
                    Toast.makeText(this, "更换悬浮窗图标成功！", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析图片失败", e);
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int calculateInSampleSize(Uri uri) throws Exception {
        final int reqWidth = 120;
        final int reqHeight = 120;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        int inSampleSize = 1;
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            final int halfHeight = options.outHeight / 2;
            final int halfWidth = options.outWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void updateBallTexture(String base64Data) {
        runOnUiThread(() -> {
            if (nativeBall == null) return;
            try {
                if (DEFAULT_BALL.equals(base64Data) || base64Data == null || base64Data.isEmpty()) {
                    nativeBall.setImageResource(R.drawable.sxb);
                } else {
                    String pureBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
                    byte[] decodedString = Base64.decode(pureBase64, Base64.NO_WRAP);
                    Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                    if (decodedByte != null) nativeBall.setImageBitmap(decodedByte);
                    else nativeBall.setImageResource(R.drawable.sxb);
                }
                nativeBall.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            } catch (Exception e) {
                nativeBall.setImageResource(R.drawable.sxb);
            }
        });
    }

    private void syncTextureToWebView(String base64) {
        executeJavaScript("if(window.onNativeBallTextureSynced){window.onNativeBallTextureSynced('" + base64 + "');}");
    }

    private void executeJavaScript(final String script) {
        if (myWebView == null) return;
        myWebView.post(() -> {
            try {
                String pureScript = script;
                if (pureScript.startsWith("javascript:")) {
                    pureScript = pureScript.substring("javascript:".length());
                }
                myWebView.evaluateJavascript(pureScript, null);
            } catch (Exception e) {
                Log.e(TAG, "执行JS失败", e);
            }
        });
    }

    private String getSavedBallBase64() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(KEY_BALL_BASE64, DEFAULT_BALL);
    }

    private void saveBallBase64(String base64) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_BALL_BASE64, base64).apply();
    }

    private void cleanUpWindow() {
        if (windowManager != null) {
            try {
                if (isWebViewAttached && myWebView != null) {
                    myWebView.loadUrl("about:blank");
                    windowManager.removeView(myWebView);
                }
            } catch (Exception e) { Log.e(TAG, "销毁画布异常", e); }

            try {
                if (isBallAttached && nativeBall != null) {
                    windowManager.removeView(nativeBall);
                }
            } catch (Exception e) { Log.e(TAG, "销毁悬浮球异常", e); }

            isWebViewAttached = false;
            isBallAttached = false;
        }
    }

    // ==========================================
    // 5. 悬浮球手势监听器
    // ==========================================

    private class FloatingBallTouchListener implements View.OnTouchListener {
        private int startWindowY;
        private float startTouchX, startTouchY;
        private boolean isMoving = false;
        private boolean isLongPressed = false;
        private int clickCount = 0;

        private final Runnable longPressRunnable = () -> {
            if (!isMoving) {
                isLongPressed = true;
                if (nativeBall != null) {
                    nativeBall.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    nativeBall.setAlpha(1.0f);
                }
                triggerNativeImagePicker();
            }
        };

        private Runnable singleClickRunnable;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (nativeBall == null || nativeBall.getVisibility() != View.VISIBLE) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startWindowY = ballLayoutParams.y;
                    startTouchX = event.getRawX();
                    startTouchY = event.getRawY();
                    isMoving = isLongPressed = false;
                    v.setAlpha(0.6f);
                    mainHandler.postDelayed(longPressRunnable, 500);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - startTouchX;
                    float deltaY = event.getRawY() - startTouchY;

                    if (Math.abs(deltaX) > 8 || Math.abs(deltaY) > 8) {
                        if (!isMoving) {
                            isMoving = true;
                            mainHandler.removeCallbacks(longPressRunnable);
                        }
                    }

                    if (isLongPressed) return true;

                    int newWindowY = startWindowY + (int) deltaY;
                    int maxMarginY = screenHeight - ballSize - safetyPadding;
                    ballLayoutParams.y = Math.max(safetyPadding, Math.min(newWindowY, maxMarginY));
                    updateBallWindowLayout();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1.0f);
                    mainHandler.removeCallbacks(longPressRunnable);

                    if (isLongPressed) return true;

                    if (!isMoving) {
                        clickCount++;
                        if (clickCount == 1) {
                            singleClickRunnable = () -> {
                                clickCount = 0;
                                v.performClick();
                                maximizeApp();
                            };
                            mainHandler.postDelayed(singleClickRunnable, 250);
                        } else if (clickCount == 2) {
                            mainHandler.removeCallbacks(singleClickRunnable);
                            clickCount = 0;
                            saveBallBase64(DEFAULT_BALL);
                            updateBallTexture(DEFAULT_BALL);
                            syncTextureToWebView(DEFAULT_BALL);
                            Toast.makeText(MainActivity.this, "已恢复默认图案", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        lastBallY = ballLayoutParams.y;
                    }
                    return true;
            }
            return false;
        }
    }

    // ==========================================
    // 6. JavaScript 互动桥梁
    // ==========================================

    @Keep
    public class AndroidBridge {
        @JavascriptInterface
        public void minimizeApp(boolean shouldMinimize) {
            if (shouldMinimize) minimizeToBall();
        }

        @JavascriptInterface
        public void closeApp() {
            runOnUiThread(() -> {
                isPageLoaded = false;
                cleanUpWindow();
                finish();
                finishAndRemoveTask();
                android.os.Process.killProcess(android.os.Process.myPid());
            });
        }

        @JavascriptInterface
        public void changeBallTexture(String base64Data) {
            if (base64Data == null) return;
            saveBallBase64(base64Data);
            updateBallTexture(base64Data);
        }
    }
}