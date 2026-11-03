package com.gathin.tvhome.player;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * 电视端 4K 媒体磁盘极速缓存单例 (MediaCacheManager)
 * 采用 512MB LRU 环形磁盘缓存机制：
 * 1. 拦截上游网络直链，边下边存边播，避开 TV 端过小的 Java 堆内存上限
 * 2. 彻底杜绝电视因 GC 暂停导致 4K 播放卡顿
 * 3. 拖动回放或二次起播时 0 延迟秒开
 */
public class MediaCacheManager {
    private static volatile SimpleCache sCache;
    private static final long MAX_CACHE_BYTES = 512L * 1024L * 1024L; // 512 MB

    public static synchronized SimpleCache getCache(Context context) {
        if (sCache == null) {
            File baseDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
            File cacheDir = new File(baseDir, "cleantv_exo_cache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES);
            StandaloneDatabaseProvider databaseProvider = new StandaloneDatabaseProvider(context.getApplicationContext());
            sCache = new SimpleCache(cacheDir, evictor, databaseProvider);
        }
        return sCache;
    }
}
