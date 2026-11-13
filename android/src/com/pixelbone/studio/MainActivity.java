package com.pixelbone.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 像素骨骼 PixelBone Studio —— 单文件 WebApp 的原生壳。
 *
 * 只做四件事：
 *   1. 全屏横屏承载 assets/index.html（硬件加速 WebView，画布走 GPU）；
 *   2. 桥接 <input type="file"> 的图片选择（导入 PNG 素材）；
 *   3. 桥接 blob 下载（导出图集 PNG / Godot JSON）到「下载/像素骨骼/」；
 *   4. 还原 window.alert / confirm / prompt（WebView 默认会静默吞掉）。
 */
public class MainActivity extends Activity {

    private static final String PAGE = "file:///android_asset/index.html";
    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView web;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 编辑器长时间对着画布，别让屏幕黑掉
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        web = new WebView(this);
        web.setBackgroundColor(0xFFF7F7F9);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 单文件离线页面，不需要安全源限制；保持 file:// 读取 assets 正常
            s.setSafeBrowsingEnabled(false);
        }

        web.addJavascriptInterface(new SaveBridge(), "AndroidSave");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new Chrome());

        setContentView(web);
        web.loadUrl(PAGE);
        web.requestFocus();
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
    }

    /* ---------------- 全屏沉浸，系统栏滑出后自动收回 ---------------- */

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidSave");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------------- 返回键：确认退出（编辑器状态不落盘） ---------------- */

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("退出 像素骨骼？")
                .setMessage("当前工程（骨骼节点、帧、导入的素材）不会被保存，退出即丢失。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    /* ---------------- 文件选择回调（导入 PNG 素材） ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(result);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ---------------- WebChromeClient：文件选择 + JS 对话框 ---------------- */

    private class Chrome extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = callback;
            try {
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQ_FILE_CHOOSER);
                return true;
            } catch (Throwable t) {
                pendingFileCallback = null;
                try {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("image/*");
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    pendingFileCallback = callback;
                    startActivityForResult(fallback, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t2) {
                    pendingFileCallback = null;
                    toast("无法打开图片选择器：" + t2.getMessage());
                    return false;
                }
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("好", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { r.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { r.cancel(); }
                    })
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { r.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final JsPromptResult r) {
            final EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSelection(input.getText().length());
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { r.cancel(); }
                    })
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            r.confirm(input.getText().toString());
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { r.cancel(); }
                    })
                    .show();
            return true;
        }
    }

    /* ---------------- JS 桥：把 blob 导出落盘 ---------------- */

    private class SaveBridge {

        @JavascriptInterface
        public void save(String name, String mime, String base64) {
            saveFile(name, mime, base64);
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    MainActivity.toast(MainActivity.this, msg);
                }
            });
        }
    }

    private void saveFile(final String rawName, final String rawMime, final String base64) {
        final String name = sanitize(rawName);
        final String mime = (rawMime == null || rawMime.isEmpty())
                ? "application/octet-stream" : rawMime;
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        msg = Api29.saveToDownloads(MainActivity.this, name, mime, data);
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir == null) dir = getFilesDir();
                        if (!dir.exists()) dir.mkdirs();
                        File f = uniqueFile(dir, name);
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(data);
                        fos.flush();
                        fos.close();
                        msg = "已导出：" + f.getAbsolutePath();
                    }
                } catch (Throwable t) {
                    msg = "导出失败：" + t.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        MainActivity.toast(MainActivity.this, m);
                    }
                });
            }
        }, "pixelbone-export").start();
    }

    /** Android 10+：写进公共下载目录 /Download/像素骨骼/，无需任何权限。 */
    private static class Api29 {
        static String saveToDownloads(Activity act, String name, String mime, byte[] data)
                throws Exception {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/像素骨骼");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = act.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("系统下载库拒绝了写入");
            OutputStream os = act.getContentResolver().openOutputStream(uri);
            if (os == null) throw new java.io.IOException("无法打开输出流");
            os.write(data);
            os.flush();
            os.close();
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            act.getContentResolver().update(uri, done, null, null);
            return "已导出到 下载/像素骨骼/" + name;
        }
    }

    private static String sanitize(String name) {
        String n = (name == null || name.trim().isEmpty()) ? "export.bin" : name.trim();
        n = n.replace('\\', '_').replace('/', '_').replace(':', '_');
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = (dot > 0 && n.length() - dot <= 8) ? n.substring(dot) : "";
            n = n.substring(0, 60) + ext;
        }
        return n;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    static void toast(Activity act, String msg) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void toast(String msg) {
        toast(this, msg);
    }
}