package com.kelvin.supergallery;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.LruCache;
import android.util.Size;
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

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kallery — native shell around the web app in /docs.
 *
 * The page is served from https://appassets.androidplatform.net. On top of the
 * bundled files, this activity answers three extra paths straight from the
 * tablet's photo library (MediaStore), so nothing is ever copied:
 *   /thumb/{i|v}/{id}?s=size   thumbnail (cached on disk)
 *   /media/{i|v}/{id}?m=mime   the original file, with byte ranges for video seeking
 *   /ext?u=contentUri          a file opened from another app
 * window.SGNative exposes the library listing, permissions, share and trash.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILES = 41, REQ_FOLDER = 42, REQ_PERM = 43, REQ_WRITE = 44;

    private WebView web;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCb;
    private ValueCallback<Uri[]> fileCb;
    private volatile boolean folderNext = false;
    private volatile String viewIntent;
    private String writeCb;
    private boolean pageReady = false;

    private final ExecutorService bg = Executors.newFixedThreadPool(2);
    private File thumbDir;
    private final LruCache<String, byte[]> thumbMem = new LruCache<String, byte[]>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };
    private WebViewAssetLoader loader;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        styleBars();
        clearShareCache();
        thumbDir = new File(getCacheDir(), "thumbs");
        thumbDir.mkdirs();
        bg.execute(this::trimThumbs);
        captureViewIntent(getIntent());

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        loader = new WebViewAssetLoader.Builder()
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
                return intercept(r);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) { pageReady = true; }
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureViewIntent(intent);
        if (viewIntent != null) js("window.SG_native&&SG_native('view')");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) js("window.SG_native&&SG_native('resume')");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullscreenView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_PERM) return;
        String a = access();
        boolean blocked = "none".equals(a) && !shouldShowRequestPermissionRationale(mainPermission());
        js("window.SG_native&&SG_native('perm','" + a + "'," + blocked + ")");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WRITE) {
            if (writeCb != null) js("SG_native('cb','" + writeCb + "'," + (res == RESULT_OK) + ")");
            writeCb = null;
            return;
        }
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

    /* ------------------------------------------------------------------ */
    /*  Serving the photo library to the page                              */
    /* ------------------------------------------------------------------ */

    private WebResourceResponse intercept(WebResourceRequest r) {
        Uri u = r.getUrl();
        if (!HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null) return null;
        try {
            if (path.startsWith("/thumb/")) {
                String[] p = path.split("/");
                return thumb(p[2], Long.parseLong(p[3]), parseInt(u.getQueryParameter("s"), 448), u.getQueryParameter("v"));
            }
            if (path.startsWith("/media/")) {
                String[] p = path.split("/");
                Uri cu = contentUri(p[2], Long.parseLong(p[3]));
                return stream(cu, mimeOr(u.getQueryParameter("m"), cu), r.getRequestHeaders());
            }
            if (path.equals("/ext")) {
                Uri cu = Uri.parse(u.getQueryParameter("u"));
                return stream(cu, mimeOr(u.getQueryParameter("m"), cu), r.getRequestHeaders());
            }
        } catch (Exception e) {
            return notFound();
        }
        return loader.shouldInterceptRequest(u);
    }

    private String mimeOr(String m, Uri cu) {
        if (m != null && !m.isEmpty()) return m;
        String t = getContentResolver().getType(cu);
        return t != null ? t : "application/octet-stream";
    }

    private static int parseInt(String s, int d) {
        try { return Integer.parseInt(s); } catch (Exception e) { return d; }
    }

    private static Uri contentUri(String kind, long id) {
        Uri base = "v".equals(kind)
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        return ContentUris.withAppendedId(base, id);
    }

    private WebResourceResponse thumb(String kind, long id, int size, String v) throws IOException {
        size = Math.max(64, Math.min(size, 2048));
        String key = kind + id + "_" + size + "_" + (v == null ? "0" : v.replaceAll("[^0-9]", ""));
        byte[] b = thumbMem.get(key);
        if (b == null) {
            File f = new File(thumbDir, key + ".jpg");
            if (f.exists() && f.length() > 0) {
                b = Files.readAllBytes(f.toPath());
            } else {
                Bitmap bm = getContentResolver().loadThumbnail(contentUri(kind, id), new Size(size, size), null);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, size > 600 ? 86 : 80, o);
                bm.recycle();
                b = o.toByteArray();
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(b); } catch (Exception ignored) {}
            }
            thumbMem.put(key, b);
        }
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=31536000");
        return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
    }

    /** Stream a file, honouring a Range header so videos can seek. */
    private WebResourceResponse stream(Uri uri, String mime, Map<String, String> reqHeaders) throws IOException {
        AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
        if (afd == null) return notFound();
        long len = afd.getLength();
        if (len < 0) len = afd.getParcelFileDescriptor().getStatSize();
        InputStream in = afd.createInputStream();
        String range = null;
        if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet())
            if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();

        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-store");
        if (range != null && range.startsWith("bytes=") && len > 0) {
            String[] p = range.substring(6).split(",")[0].trim().split("-", 2);
            long start, end;
            if (p[0].isEmpty()) { start = Math.max(0, len - Long.parseLong(p[1])); end = len - 1; }
            else {
                start = Long.parseLong(p[0]);
                end = (p.length > 1 && !p[1].isEmpty()) ? Math.min(Long.parseLong(p[1]), len - 1) : len - 1;
            }
            if (start >= len) { in.close(); return notFound(); }
            long skipped = 0;
            while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
            long n = end - start + 1;
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            h.put("Content-Length", String.valueOf(n));
            return new WebResourceResponse(mime, null, 206, "Partial Content", h, new Bounded(in, n));
        }
        if (len > 0) h.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(mime, null, 200, "OK", h, in);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static class Bounded extends InputStream {
        private final InputStream in;
        private long left;
        Bounded(InputStream in, long n) { this.in = in; this.left = n; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = in.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(in.available(), left); }
        @Override public void close() throws IOException { in.close(); }
    }

    private void trimThumbs() {
        File[] fs = thumbDir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total < 350L * 1024 * 1024) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) {
            if (total < 220L * 1024 * 1024) break;
            total -= f.length();
            f.delete();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Library listing                                                     */
    /* ------------------------------------------------------------------ */

    private String query(boolean trashed) {
        if (trashed && Build.VERSION.SDK_INT < 30) return "[]";
        if ("none".equals(access())) return "[]";
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        List<String> proj = new ArrayList<>(Arrays.asList(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.ORIENTATION));
        if (Build.VERSION.SDK_INT >= 30) proj.add(MediaStore.MediaColumns.DATE_EXPIRES);
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + "," + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")");
        if (Build.VERSION.SDK_INT >= 30)
            args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, trashed ? MediaStore.MATCH_ONLY : MediaStore.MATCH_EXCLUDE);

        StringBuilder sb = new StringBuilder("[");
        try (Cursor c = getContentResolver().query(files, proj.toArray(new String[0]), args, null)) {
            if (c != null) {
                boolean first = true;
                int cols = c.getColumnCount();
                while (c.moveToNext()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('[').append(c.getLong(0))
                      .append(',').append(c.getInt(1))
                      .append(',').append(JSONObject.quote(str(c, 2)))
                      .append(',').append(JSONObject.quote(str(c, 3)))
                      .append(',').append(c.getLong(4))
                      .append(',').append(c.getLong(5))
                      .append(',').append(c.getLong(6))
                      .append(',').append(c.getInt(7))
                      .append(',').append(c.getInt(8))
                      .append(',').append(c.getLong(9))
                      .append(',').append(c.getLong(10))
                      .append(',').append(JSONObject.quote(str(c, 11)))
                      .append(',').append(c.getInt(12))
                      .append(',').append(cols > 13 ? c.getLong(13) : 0)
                      .append(']');
                }
            }
        } catch (Exception ignored) {}
        return sb.append(']').toString();
    }

    private static String str(Cursor c, int i) { return c.isNull(i) ? "" : c.getString(i); }

    private String generation() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return String.valueOf(MediaStore.getGeneration(this, MediaStore.VOLUME_EXTERNAL_PRIMARY)); }
            catch (Exception ignored) {}
        }
        return "";
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private String mainPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private String access() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) return "full";
            if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return "partial";
            return "none";
        }
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE) ? "full" : "none";
    }

    private ArrayList<Uri> parseIds(String ids) {
        ArrayList<Uri> out = new ArrayList<>();
        if (ids == null) return out;
        for (String part : ids.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) try { out.add(contentUri(kv[0], Long.parseLong(kv[1]))); } catch (Exception ignored) {}
        }
        return out;
    }

    private void captureViewIntent(Intent i) {
        if (i == null || i.getData() == null) return;
        String a = i.getAction();
        if (a == null || !(a.equals(Intent.ACTION_VIEW) || a.endsWith(".REVIEW"))) return;
        try {
            Uri u = i.getData();
            String mime = i.getType();
            if (mime == null) mime = getContentResolver().getType(u);
            JSONObject o = new JSONObject();
            o.put("uri", u.toString());
            o.put("mime", mime == null ? "" : mime);
            if ("media".equals(u.getAuthority())) {
                try {
                    o.put("id", String.valueOf(ContentUris.parseId(u)));
                    boolean video = u.toString().contains("/video/") || (mime != null && mime.startsWith("video/"));
                    o.put("kind", video ? "v" : "i");
                } catch (Exception ignored) {}
            }
            viewIntent = o.toString();
        } catch (Exception ignored) {}
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /* ------------------------------------------------------------------ */

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
        if (fs != null) for (File f : fs) deleteTree(f);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void startWrite(PendingIntent pi, String cb) {
        runOnUiThread(() -> {
            try {
                writeCb = cb;
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e) {
                writeCb = null;
                js("SG_native('cb','" + cb + "',false)");
            }
        });
    }

    /** Methods callable from the page as window.SGNative.* */
    private class Bridge {
        private final Map<String, String[]> staged = new HashMap<>();

        /* ---- device library ---- */
        @JavascriptInterface public String access() { return MainActivity.this.access(); }

        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(() -> {
                String[] p;
                if (Build.VERSION.SDK_INT >= 34) p = new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
                else if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
                else p = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
                requestPermissions(p, REQ_PERM);
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void load(String cb) {
            bg.execute(() -> {
                String json = "{\"items\":" + query(false) + ",\"trash\":" + query(true) + ",\"gen\":\"" + generation() + "\"}";
                js("SG_native('cb','" + cb + "'," + json + ")");
            });
        }

        @JavascriptInterface public String generation() { return MainActivity.this.generation(); }

        @JavascriptInterface
        public String takeViewIntent() { String s = viewIntent; viewIntent = null; return s == null ? "" : s; }

        /** Play a library video in the native player. */
        @JavascriptInterface
        public void playVideo(String kind, String id) {
            try {
                Uri u = contentUri(kind, Long.parseLong(id));
                runOnUiThread(() -> VideoActivity.play(MainActivity.this, u));
            } catch (Exception ignored) {}
        }

        /** Play a video opened from another app. */
        @JavascriptInterface
        public void playUri(String uri) {
            try {
                Uri u = Uri.parse(uri);
                runOnUiThread(() -> VideoActivity.play(MainActivity.this, u));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void shareIds(String ids) {
            ArrayList<Uri> uris = parseIds(ids);
            if (uris.isEmpty()) return;
            boolean img = false, vid = false;
            for (String part : ids.split(",")) { if (part.startsWith("v:")) vid = true; else img = true; }
            String type = img && vid ? "*/*" : vid ? "video/*" : "image/*";
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

        /** Move to the tablet's trash (value=true) or restore from it (false). */
        @JavascriptInterface
        public void trash(String ids, boolean value, String cb) {
            ArrayList<Uri> uris = parseIds(ids);
            if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) { js("SG_native('cb','" + cb + "',false)"); return; }
            startWrite(MediaStore.createTrashRequest(getContentResolver(), uris, value), cb);
        }

        /** Delete for good. */
        @JavascriptInterface
        public void remove(String ids, String cb) {
            ArrayList<Uri> uris = parseIds(ids);
            if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) { js("SG_native('cb','" + cb + "',false)"); return; }
            startWrite(MediaStore.createDeleteRequest(getContentResolver(), uris), cb);
        }

        /* ---- used by the import mode (kept for compatibility) ---- */
        @JavascriptInterface public void nextIsFolder() { folderNext = true; }

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
            for (String tok : toks.split(",")) {
                String[] st = staged.get(tok);
                if (st != null) uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", new File(st[0])));
            }
            if (uris.isEmpty()) return;
            final Intent i;
            if (uris.size() == 1) { i = new Intent(Intent.ACTION_SEND); i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); }
            else { i = new Intent(Intent.ACTION_SEND_MULTIPLE); i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); }
            i.setType("*/*");
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
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Kallery");
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
