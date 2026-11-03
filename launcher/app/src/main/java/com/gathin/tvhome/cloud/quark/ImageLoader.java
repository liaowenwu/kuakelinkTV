package com.gathin.tvhome.cloud.quark;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简纯净的官方网盘海报异步网络加载器（纯网盘 URL 直出，带轻量内存 + 磁盘两级缓存）
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final long MAX_DISK_BYTES = 48L * 1024 * 1024;
    private static final ConcurrentHashMap<String, Bitmap> memoryCache = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile File diskDir;

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    /** Call once from Application/Activity onCreate so images survive process restarts. */
    public static void init(Context context) {
        if (diskDir != null) return;
        synchronized (ImageLoader.class) {
            if (diskDir == null) {
                File dir = new File(context.getApplicationContext().getCacheDir(), "img_cache");
                if (!dir.exists()) dir.mkdirs();
                diskDir = dir;
                executor.execute(ImageLoader::pruneDiskCache);
            }
        }
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
        Bitmap fromDisk = readFromDisk(urlStr);
        if (fromDisk != null) {
            memoryCache.put(urlStr, fromDisk);
            return fromDisk;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", QuarkTvClient.USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                byte[] bytes = readAll(conn.getInputStream());
                if (bytes == null || bytes.length == 0) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bmp != null) writeToDisk(urlStr, bytes);
                return bmp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bitmap readFromDisk(String urlStr) {
        File dir = diskDir;
        if (dir == null) return null;
        File f = new File(dir, hashKey(urlStr));
        if (!f.isFile() || f.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            f.delete();
            return null;
        }
    }

    private static void writeToDisk(String urlStr, byte[] bytes) {
        File dir = diskDir;
        if (dir == null) return;
        File tmp = new File(dir, hashKey(urlStr) + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (Throwable t) {
            tmp.delete();
            return;
        }
        File dest = new File(dir, hashKey(urlStr));
        if (!tmp.renameTo(dest)) {
            tmp.delete();
        }
    }

    /** Keep the newest entries under MAX_DISK_BYTES so the TV's cache partition is never filled. */
    private static void pruneDiskCache() {
        File dir = diskDir;
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK_BYTES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        AtomicLong remaining = new AtomicLong(total);
        for (File f : files) {
            if (remaining.get() <= MAX_DISK_BYTES) break;
            long len = f.length();
            if (f.delete()) remaining.addAndGet(-len);
        }
    }

    private static byte[] readAll(InputStream is) {
        if (is == null) return null;
        try (InputStream in = is) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hashKey(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
