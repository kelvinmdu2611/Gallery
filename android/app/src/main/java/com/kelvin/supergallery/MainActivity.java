package com.kelvin.supergallery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Super Gallery — a thin native shell around the web app in /docs.
 * The page is served from https://appassets.androidplatform.net so IndexedDB
 * storage works like on a normal website. Adds native file/folder picking,
 * sharing and saving to the device gallery.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILES = 41, REQ_FOLDER = 42;

    private WebView web;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCb;
    private ValueCallback<Uri[]> fileCb;
    private volatile boolean folderNext = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        styleBars();
        clearShareCache();

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        web.setBackgroundColor(isNight() ? Color.BLACK : Color.WHITE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    if (folderNext) {
                        folderNext = false;
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
                    } else {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(Intent.createChooser(i, "Choose photos & videos"), REQ_FILES);
                    }
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullscreenView != null) { cb.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                root.removeView(fullscreenView);
                fullscreenView = null;
                if (fullscreenCb != null) fullscreenCb.onCustomViewHidden();
                fullscreenCb = null;
            }
        });

        web.addJavascriptInterface(new Bridge(), "SGNative");

        if (state != null) web.restoreState(state);
        else web.loadUrl(START);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullscreenView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (fileCb == null) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            if (req == REQ_FILES) {
                ArrayList<Uri> list = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) list.add(clip.getItemAt(i).getUri());
                else if (data.getData() != null) list.add(data.getData());
                result = list.toArray(new Uri[0]);
            } else if (req == REQ_FOLDER && data.getData() != null) {
                Uri tree = data.getData();
                ArrayList<Uri> list = new ArrayList<>();
                collectMedia(tree, DocumentsContract.getTreeDocumentId(tree), list, 0);
                result = list.toArray(new Uri[0]);
            }
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
    }

    /** Walk a picked folder (and sub-folders) and gather every photo and video. */
    private void collectMedia(Uri tree, String docId, ArrayList<Uri> out, int depth) {
        if (depth > 6 || out.size() > 20000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), mime = c.getString(1), name = c.getString(2);
                if (name != null && name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collectMedia(tree, id, out, depth + 1);
                else if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/")))
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } catch (Exception ignored) {}
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void styleBars() {
        Window w = getWindow();
        boolean night = isNight();
        w.setStatusBarColor(night ? Color.BLACK : Color.WHITE);
        w.setNavigationBarColor(night ? Color.BLACK : Color.WHITE);
        int flags = 0;
        if (!night) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private File shareDir() {
        File d = new File(getCacheDir(), "share");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void clearShareCache() {
        File[] fs = shareDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Methods callable from the page as window.SGNative.* */
    private class Bridge {
        private final Map<String, String[]> staged = new HashMap<>(); // token -> {path, name, mime}

        @JavascriptInterface
        public void nextIsFolder() { folderNext = true; }

        @JavascriptInterface
        public String begin(String name, String mime) {
            String tok = UUID.randomUUID().toString().substring(0, 8);
            String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            File f = new File(new File(shareDir(), tok), safe);
            f.getParentFile().mkdirs();
            f.delete();
            staged.put(tok, new String[]{f.getAbsolutePath(), name, mime});
            return tok;
        }

        @JavascriptInterface
        public boolean append(String tok, String b64) {
            String[] st = staged.get(tok);
            if (st == null) return false;
            try (FileOutputStream o = new FileOutputStream(st[0], true)) {
                o.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void share(String toks) {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (String tok : toks.split(",")) {
                String[] st = staged.get(tok);
                if (st == null) continue;
                uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", new File(st[0])));
                String top = st[2].contains("/") ? st[2].substring(0, st[2].indexOf('/')) : "*";
                type = type == null ? st[2] : (type.equals(st[2]) ? type : (type.startsWith(top + "/") ? top + "/*" : "*/*"));
            }
            if (uris.isEmpty()) return;
            final Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(type);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(i, "Share")));
        }

        @JavascriptInterface
        public boolean save(String tok) {
            String[] st = staged.remove(tok);
            if (st == null) return false;
            boolean video = st[2].startsWith("video/");
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, st[1]);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, st[2]);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Super Gallery");
            Uri coll = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentResolver cr = getContentResolver();
            Uri dest = null;
            try {
                dest = cr.insert(coll, cv);
                if (dest == null) return false;
                try (InputStream in = new FileInputStream(st[0]); OutputStream out = cr.openOutputStream(dest)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return true;
            } catch (Exception e) {
                if (dest != null) try { cr.delete(dest, null, null); } catch (Exception ignored) {}
                return false;
            } finally {
                new File(st[0]).delete();
            }
        }
    }
}
