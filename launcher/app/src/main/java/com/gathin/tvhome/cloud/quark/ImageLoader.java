package com.gathin.tvhome.cloud.quark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简纯净的官方网盘海报异步网络加载器（纯网盘 URL 直出，带轻量内存复用）
 */
public class ImageLoader {
    private static final ConcurrentHashMap<String, Bitmap> memoryCache = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    public static void load(String url, ImageView imageView) {
        if (url == null || url.isEmpty() || imageView == null) return;
        imageView.setTag(url);

        Bitmap cached = memoryCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        executor.execute(() -> {
            Bitmap bmp = download(url);
            if (bmp != null) {
                memoryCache.put(url, bmp);
                mainHandler.post(() -> {
                    if (url.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    public static void load(String url, Callback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onLoaded(null);
            return;
        }

        Bitmap cached = memoryCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onLoaded(cached);
            return;
        }

        executor.execute(() -> {
            Bitmap bmp = download(url);
            if (bmp != null) {
                memoryCache.put(url, bmp);
                mainHandler.post(() -> {
                    if (callback != null) callback.onLoaded(bmp);
                });
            } else {
                mainHandler.post(() -> {
                    if (callback != null) callback.onLoaded(null);
                });
            }
        });
    }

    private static Bitmap download(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", QuarkTvClient.USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeStream(is, null, opts);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
