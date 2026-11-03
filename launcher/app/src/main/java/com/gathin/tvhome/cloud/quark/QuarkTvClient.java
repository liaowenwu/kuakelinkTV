package com.gathin.tvhome.cloud.quark;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 夸克网盘 TV 官方 OpenAPI 客户端
 * 采用 Android TV 官方授权协议（ExtScreen / Eskit），支持：
 * 1. 扫码一键授权登录（手机夸克扫码，免繁琐输入）
 * 2. 长期有效的 Refresh Token 自动无感刷新机制（长久保活）
 * 3. 4K 原画与杜比视界（Dolby Vision）视频流直链解析
 * 4. 网盘目录树遍历与视频文件自动检索
 */
public class QuarkTvClient {
    private static final String TAG = "QuarkTvClient";

    private static final String PREF_NAME = "quark_tv_config";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_EXPIRES_AT = "token_expires_at";

    // 官方 TV 端配置常量
    private static final String OPEN_API_HOST = "https://open-api-drive.quark.cn";
    private static final String TOKEN_API_URL = "http://api.extscreen.com/quarkdrive/token";
    private static final String CLIENT_ID = "d3194e61504e493eb6222857bccfed94";
    private static final String SIGN_KEY = "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d";
    private static final String APP_VER = "1.5.6";
    private static final String CHANNEL = "CP";
    private static final String DEVICE_BRAND = "Xiaomi";
    private static final String PLATFORM = "tv";
    private static final String DEVICE_NAME = "MiTV";
    private static final String DEVICE_MODEL = "MiTV";
    private static final String BUILD_DEVICE = "MiTV";
    private static final String BUILD_PRODUCT = "MiTV";
    private static final String DEVICE_GPU = "Mali";
    private static final String ACTIVITY_RECT = "{}";
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1";

    private static volatile QuarkTvClient instance;
    private final Context appContext;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 目录与视频 Fid 缓存，加速二次起播
    private final Map<String, String> dirFidCache = new ConcurrentHashMap<>();
    private final Map<String, QuarkFile> videoFileCache = new ConcurrentHashMap<>();
    // 独立公共海报库缓存 (/来自：分享/海报)
    private final Map<String, String> centralPosterCache = new ConcurrentHashMap<>();
    private final Map<String, String> centralBackdropCache = new ConcurrentHashMap<>();

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String errorMsg);
    }

    public static class QrResult {
        public final String queryToken;
        public final Bitmap qrBitmap;

        public QrResult(String queryToken, Bitmap qrBitmap) {
            this.queryToken = queryToken;
            this.qrBitmap = qrBitmap;
        }
    }

    public static class QuarkFile {
        public final String fid;
        public final String parentFid;
        public final String filename;
        public final long size;
        public final boolean isDir;
        public final String fileType;
        public final String thumbnailUrl;

        public QuarkFile(String fid, String parentFid, String filename, long size, boolean isDir, String fileType, String thumbnailUrl) {
            this.fid = fid;
            this.parentFid = parentFid;
            this.filename = filename;
            this.size = size;
            this.isDir = isDir;
            this.fileType = fileType;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    public static class CloudCategory {
        public final String name;
        public final List<CloudMovie> items;

        public CloudCategory(String name, List<CloudMovie> items) {
            this.name = name;
            this.items = items;
        }
    }

    public static class CloudMovie implements java.io.Serializable {
        public static final int TYPE_SINGLE = 0;
        public static final int TYPE_COLLECTION_MOVIES = 1;
        public static final int TYPE_COLLECTION_SEASONS = 2;

        public final String title;
        public final String meta;
        public final String path;
        public final String fid;
        public final boolean isDir;
        public final String thumbnailUrl;
        public final int itemType;
        public final String badgeText;
        public final List<CloudMovie> subItems;

        public CloudMovie(String title, String meta, String path, String fid, boolean isDir, String thumbnailUrl) {
            this(title, meta, path, fid, isDir, thumbnailUrl, TYPE_SINGLE, null, null);
        }

        public CloudMovie(String title, String meta, String path, String fid, boolean isDir, String thumbnailUrl,
                          int itemType, String badgeText, List<CloudMovie> subItems) {
            this.title = title;
            this.meta = meta;
            this.path = path;
            this.fid = fid;
            this.isDir = isDir;
            this.thumbnailUrl = thumbnailUrl;
            this.itemType = itemType;
            this.badgeText = badgeText;
            this.subItems = subItems != null ? subItems : new ArrayList<>();
        }
    }

    public static class CloudEpisode implements java.io.Serializable {
        public final int episodeNumber;
        public final String name;
        public final String filename;
        public final String fid;
        public final long size;

        public CloudEpisode(int episodeNumber, String name, String filename, String fid, long size) {
            this.episodeNumber = episodeNumber;
            this.name = name;
            this.filename = filename;
            this.fid = fid;
            this.size = size;
        }
    }

    private QuarkTvClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureDeviceId();
    }

    public static void init(Context context) {
        if (instance == null) {
            synchronized (QuarkTvClient.class) {
                if (instance == null) {
                    instance = new QuarkTvClient(context);
                }
            }
        }
    }

    public static QuarkTvClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("QuarkTvClient not initialized. Call init(Context) first.");
        }
        return instance;
    }

    private String ensureDeviceId() {
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = md5(UUID.randomUUID().toString() + System.currentTimeMillis());
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }

    public boolean isLoggedIn() {
        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        return refreshToken != null && !refreshToken.trim().isEmpty();
    }

    public void logout() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_EXPIRES_AT).apply();
        dirFidCache.clear();
        videoFileCache.clear();
    }

    /**
     * 获取 TV 端扫码登录二维码
     */
    public void getLoginQrCode(Callback<QrResult> callback) {
        executor.execute(() -> {
            try {
                String pathname = "/oauth/authorize";
                Map<String, String> query = new HashMap<>();
                query.put("auth_type", "code");
                query.put("client_id", CLIENT_ID);
                query.put("scope", "netdisk");
                query.put("qrcode", "1");
                query.put("qr_width", "460");
                query.put("qr_height", "460");

                String respStr = executeOpenApi("GET", pathname, query, null, false);
                JSONObject json = new JSONObject(respStr);
                if (json.optInt("status", -1) == 0 || json.optInt("code", -1) == 0 || json.has("qr_data")) {
                    String queryToken = json.optString("query_token");
                    String qrData = json.optString("qr_data");
                    if (qrData.isEmpty()) {
                        postError(callback, "获取登录二维码失败：数据为空");
                        return;
                    }
                    byte[] bytes = Base64.decode(qrData, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    postSuccess(callback, new QrResult(queryToken, bitmap));
                } else {
                    postError(callback, "获取二维码失败：" + json.optString("error_info", "未知错误"));
                }
            } catch (Exception e) {
                Log.e(TAG, "getLoginQrCode failed", e);
                postError(callback, "网络异常：" + e.getMessage());
            }
        });
    }

    /**
     * 轮询扫码授权状态
     * @return 授权成功返回 code；仍在等待中返回 null；出错回调 onError
     */
    public void pollAuthCode(String queryToken, Callback<String> callback) {
        executor.execute(() -> {
            try {
                String pathname = "/oauth/code";
                Map<String, String> query = new HashMap<>();
                query.put("client_id", CLIENT_ID);
                query.put("scope", "netdisk");
                query.put("query_token", queryToken);

                String respStr = executeOpenApi("GET", pathname, query, null, false);
                JSONObject json = new JSONObject(respStr);
                int status = json.optInt("status", -1);
                int errno = json.optInt("errno", -1);

                if (errno == 11003) {
                    // 用户未确认授权，仍在等待
                    postSuccess(callback, null);
                    return;
                }

                if (status == 0 && json.has("code")) {
                    postSuccess(callback, json.getString("code"));
                } else if (json.has("code") && !json.getString("code").isEmpty()) {
                    postSuccess(callback, json.getString("code"));
                } else {
                    postError(callback, json.optString("error_info", "等待授权中"));
                }
            } catch (HttpException e) {
                if (e.body != null && e.body.contains("11003")) {
                    // 仍在等待扫码授权
                    postSuccess(callback, null);
                    return;
                }
                postError(callback, "查询授权状态异常：" + e.getMessage());
            } catch (Exception e) {
                postError(callback, "网络查询异常：" + e.getMessage());
            }
        });
    }

    /**
     * 将 code 兑换为持久生效的 AccessToken 和 RefreshToken
     */
    public void exchangeCodeForTokens(String code, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                boolean ok = requestToken(code, false);
                if (ok) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, "Token 交换失败");
                }
            } catch (Exception e) {
                postError(callback, "Token 交换异常：" + e.getMessage());
            }
        });
    }

    private boolean requestToken(String tokenOrCode, boolean isRefresh) throws Exception {
        String deviceId = ensureDeviceId();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String reqId = md5(deviceId + timestamp);

        JSONObject body = new JSONObject();
        body.put("req_id", reqId);
        body.put("app_ver", APP_VER);
        body.put("device_id", deviceId);
        body.put("device_brand", DEVICE_BRAND);
        body.put("platform", PLATFORM);
        body.put("device_name", DEVICE_NAME);
        body.put("device_model", DEVICE_MODEL);
        body.put("build_device", BUILD_DEVICE);
        body.put("build_product", BUILD_PRODUCT);
        body.put("device_gpu", DEVICE_GPU);
        body.put("activity_rect", ACTIVITY_RECT);
        body.put("channel", CHANNEL);

        if (isRefresh) {
            body.put("refresh_token", tokenOrCode);
        } else {
            body.put("code", tokenOrCode);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_API_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        byte[] postBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postBytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readString(is);

        JSONObject json = new JSONObject(resp);
        if (json.optInt("code", -1) == 200 && json.has("data")) {
            JSONObject data = json.getJSONObject("data");
            String accessToken = data.optString("access_token");
            String refreshToken = data.optString("refresh_token");
            long expiresIn = data.optLong("expires_in", 2592000L); // 默认 30 天

            if (!accessToken.isEmpty() && !refreshToken.isEmpty()) {
                long expiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
                prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, refreshToken)
                        .putLong(KEY_EXPIRES_AT, expiresAt)
                        .apply();
                return true;
            }
        }
        return false;
    }

    private synchronized boolean ensureAccessTokenValidSync() {
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        if (accessToken != null && !accessToken.isEmpty() && System.currentTimeMillis() < expiresAt) {
            return true;
        }

        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return false;
        }

        try {
            return requestToken(refreshToken, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh access token", e);
            return false;
        }
    }

    /**
     * 查询文件列表
     */
    public void listFiles(String parentFid, Callback<List<QuarkFile>> callback) {
        executor.execute(() -> {
            try {
                if (!ensureAccessTokenValidSync()) {
                    postError(callback, "账号未登录或登录已过期，请重新扫码");
                    return;
                }

                String pathname = "/file";
                Map<String, String> query = new HashMap<>();
                query.put("method", "list");
                query.put("parent_fid", (parentFid == null || parentFid.isEmpty()) ? "0" : parentFid);
                query.put("order_by", "3");
                query.put("desc", "1");
                query.put("page_size", "100");
                query.put("page_index", "0");

                String respStr = executeOpenApi("GET", pathname, query, null, true);
                JSONObject json = new JSONObject(respStr);
                JSONObject data = json.optJSONObject("data");
                List<QuarkFile> list = new ArrayList<>();
                if (data != null && data.has("files")) {
                    JSONArray arr = data.getJSONArray("files");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject f = arr.getJSONObject(i);
                        list.add(new QuarkFile(
                                f.optString("fid"),
                                f.optString("parent_fid"),
                                f.optString("filename"),
                                f.optLong("size"),
                                f.optInt("isdir") == 1,
                                f.optString("file_type"),
                                f.optString("thumbnail_url")
                        ));
                    }
                }
                postSuccess(callback, list);
            } catch (Exception e) {
                postError(callback, "读取文件列表异常：" + e.getMessage());
            }
        });
    }

    public List<QuarkFile> listFilesSync(String parentFid) throws Exception {
        String pathname = "/file";
        Map<String, String> query = new HashMap<>();
        query.put("method", "list");
        query.put("parent_fid", (parentFid == null || parentFid.isEmpty()) ? "0" : parentFid);
        query.put("order_by", "3");
        query.put("desc", "1");
        query.put("page_size", "100");
        List<QuarkFile> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; ; page++) {
            query.put("page_index", Integer.toString(page));
            String respStr = executeOpenApi("GET", pathname, query, null, true);
            JSONObject data = new JSONObject(respStr).optJSONObject("data");
            JSONArray files = data == null ? null : data.optJSONArray("files");
            if (files == null) throw new java.io.IOException("文件列表响应缺少 files");
            int added = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (!seen.add(f.getString("fid"))) continue;
                list.add(new QuarkFile(f.getString("fid"), f.optString("parent_fid"),
                        f.optString("filename"), f.optLong("size"), f.optInt("isdir") == 1,
                        f.optString("file_type"), f.optString("thumbnail_url")));
                added++;
            }
            if (files.length() < 100) break;
            if (added == 0) throw new java.io.IOException("文件列表分页未前进，请重试同步");
        }
        return list;
    }

    /**
     * 自动从用户夸克网盘中扫描影视分类与电影剧集库
     */
    public void fetchCinemaLibrary(Callback<List<CloudCategory>> callback) {
        executor.execute(() -> {
            try {
                if (!ensureAccessTokenValidSync()) {
                    postError(callback, "夸克网盘未登录或凭证已过期");
                    return;
                }

                List<CloudCategory> categories = new ArrayList<>();
                List<QuarkFile> rootFiles = listFilesSync("0");

                List<QuarkFile> libraryDirs = new ArrayList<>();
                List<QuarkFile> rootCatDirs = new ArrayList<>();
                List<CloudMovie> rootDirectMovies = new ArrayList<>();

                for (QuarkFile f : rootFiles) {
                    String name = f.filename.trim();
                    if (f.isDir) {
                        if (name.equals("资源库") || name.contains("分享")) {
                            libraryDirs.add(f);
                        } else if (isCategoryName(name)) {
                            rootCatDirs.add(f);
                        }
                    } else if (isVideoFile(name)) {
                        rootDirectMovies.add(new CloudMovie(
                                cleanTitle(name),
                                formatVideoMeta(f.size),
                                "/" + name,
                                f.fid,
                                false,
                                f.thumbnailUrl
                        ));
                    }
                }

                // 1. 扫描【资源库】及分享目录中的分类，保留各自的真实路径。
                for (QuarkFile shareDir : libraryDirs) {
                    List<QuarkFile> shareChildren = listFilesSync(shareDir.fid);
                    List<QuarkFile> shareSubDirs = new ArrayList<>();
                    QuarkFile posterFolder = null;

                    for (QuarkFile sc : shareChildren) {
                        if (sc.isDir && (sc.filename.trim().equals("海报") || sc.filename.trim().equalsIgnoreCase("posters"))) {
                            posterFolder = sc;
                        } else if (sc.isDir && isCategoryName(sc.filename)) {
                            // 严格仅保留 4 大影视分类文件夹，彻底排除“音乐”、“歌手分类”、“其他”等非影视目录
                            shareSubDirs.add(sc);
                        }
                    }

                    // 检索专用独立海报库目录 (/来自：分享/海报)
                    if (posterFolder != null) {
                        try {
                            centralPosterCache.clear();
                            centralBackdropCache.clear();
                            List<QuarkFile> pFiles = listFilesSync(posterFolder.fid);
                            for (QuarkFile pf : pFiles) {
                                if (pf.isDir) continue;
                                String fn = pf.filename.toLowerCase();
                                if (fn.endsWith(".jpg") || fn.endsWith(".png") || fn.endsWith(".webp") || fn.endsWith(".jpeg")) {
                                    String pure = cleanTitle(pf.filename).toLowerCase();
                                    if (pure.contains("_bg") || pure.contains("_backdrop") || pure.contains("background")) {
                                        pure = pure.replace("_bg", "").replace("_backdrop", "").replace("background", "").trim();
                                        centralBackdropCache.put(pure, pf.thumbnailUrl != null ? pf.thumbnailUrl : "");
                                    } else {
                                        pure = pure.replace("_poster", "").replace("poster", "").trim();
                                        centralPosterCache.put(pure, pf.thumbnailUrl != null ? pf.thumbnailUrl : "");
                                    }
                                }
                            }
                            Log.i(TAG, "Loaded central posters: " + centralPosterCache.size() + ", backdrops: " + centralBackdropCache.size());
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to scan central poster folder: " + e.getMessage());
                        }
                    }

                    for (QuarkFile subDir : shareSubDirs) {
                        String catName = beautifyCategoryName(subDir.filename);
                        if (catName == null) continue;

                        List<QuarkFile> grandChildren = listFilesSync(subDir.fid);
                        List<CloudMovie> movies = new ArrayList<>();
                        for (QuarkFile gc : grandChildren) {
                            if (isIgnoredFolderOrFile(gc.filename)) continue;
                            if (gc.isDir) {
                                CloudMovie cm = parseFolderIntoMovie("/" + shareDir.filename + "/" + subDir.filename, gc);
                                if (cm != null) movies.add(cm);
                            } else if (isVideoFile(gc.filename)) {
                                movies.add(new CloudMovie(
                                        cleanTitle(gc.filename),
                                        formatVideoMeta(gc.size),
                                        "/" + shareDir.filename + "/" + subDir.filename + "/" + gc.filename,
                                        gc.fid,
                                        false,
                                        gc.thumbnailUrl
                                ));
                            }
                        }

                        if (!movies.isEmpty()) {
                            categories.add(new CloudCategory(catName, movies));
                        }
                    }
                }

                // 2. 扫描根目录分类文件夹（如【电影】、【电视剧】、【动漫】、【综艺】）
                for (QuarkFile rDir : rootCatDirs) {
                    String catName = beautifyCategoryName(rDir.filename);
                    if (catName == null) continue;

                    List<QuarkFile> children = listFilesSync(rDir.fid);
                    List<CloudMovie> movies = new ArrayList<>();
                    for (QuarkFile c : children) {
                        if (isIgnoredFolderOrFile(c.filename)) continue;
                        if (c.isDir) {
                            CloudMovie cm = parseFolderIntoMovie("/" + rDir.filename, c);
                            if (cm != null) movies.add(cm);
                        } else if (isVideoFile(c.filename)) {
                            movies.add(new CloudMovie(
                                    cleanTitle(c.filename),
                                    formatVideoMeta(c.size),
                                    "/" + rDir.filename + "/" + c.filename,
                                    c.fid,
                                    false,
                                    c.thumbnailUrl
                            ));
                        }
                    }
                    if (!movies.isEmpty()) {
                        categories.add(new CloudCategory(catName, movies));
                    }
                }

                // 3. 根目录零散视频
                if (!rootDirectMovies.isEmpty()) {
                    categories.add(new CloudCategory("网盘视频", rootDirectMovies));
                }

                postSuccess(callback, categories);
            } catch (Exception e) {
                Log.e(TAG, "fetchCinemaLibrary failed", e);
                postError(callback, "扫描网盘影视库失败：" + e.getMessage());
            }
        });
    }

    private CloudMovie parseFolderIntoMovie(String parentPath, QuarkFile folder) throws Exception {
        String currentPath = parentPath + "/" + folder.filename;
        String thumbnail = findCentralPosterUrl(folder.filename);
        if (thumbnail == null) thumbnail = folder.thumbnailUrl;
        List<QuarkFile> files = listFilesSync(folder.fid);
        List<CloudMovie> groups = new ArrayList<>();
        for (QuarkFile child : files) {
            if (!child.isDir || isIgnoredFolderOrFile(child.filename)) continue;
            CloudMovie parsed = parseFolderIntoMovie(currentPath, child);
            if (parsed == null) continue;
            if (parsed.subItems.isEmpty()) {
                groups.add(new CloudMovie(child.filename, parsed.meta, currentPath + "/" + child.filename,
                        child.fid, true, parsed.thumbnailUrl));
            } else {
                // Nested versions/seasons remain separate, with their relative directory names.
                for (CloudMovie group : parsed.subItems) {
                    groups.add(new CloudMovie(child.filename + "/" + group.title, group.meta,
                            group.path, group.fid, true, group.thumbnailUrl));
                }
            }
        }
        return describeFolder(currentPath, folder, files, groups, thumbnail);
    }

    static CloudMovie describeFolder(String path, QuarkFile folder, List<QuarkFile> files,
                                     List<CloudMovie> childGroups, String thumbnail) {
        List<QuarkFile> videos = new ArrayList<>();
        for (QuarkFile file : files) {
            if (!file.isDir && !isIgnoredFolderOrFile(file.filename) && isVideoFile(file.filename)) {
                videos.add(file);
            }
        }
        if (!childGroups.isEmpty()) {
            List<CloudMovie> groups = new ArrayList<>();
            if (!videos.isEmpty()) {
                groups.add(new CloudMovie("正片", "共 " + videos.size() + " 个视频", path,
                        folder.fid, true, thumbnail));
            }
            groups.addAll(childGroups);
            return new CloudMovie(cleanTitle(folder.filename), "共 " + groups.size() + " 个分组",
                    path, folder.fid, true, thumbnail, CloudMovie.TYPE_COLLECTION_SEASONS,
                    groups.size() + " 个分组", groups);
        }
        if (videos.size() == 1) {
            QuarkFile video = videos.get(0);
            return new CloudMovie(cleanTitle(folder.filename), formatVideoMeta(video.size),
                    path + "/" + video.filename, video.fid, false, thumbnail);
        }
        if (!videos.isEmpty()) {
            return new CloudMovie(cleanTitle(folder.filename), "共 " + videos.size() + " 集",
                    path, folder.fid, true, thumbnail);
        }
        return null;
    }

    public void findPlayableFile(String folderFid, String path, Callback<QuarkFile> callback) {
        if (folderFid != null && !folderFid.isEmpty()) {
            executor.execute(() -> {
                try {
                    if (!ensureAccessTokenValidSync()) {
                        postError(callback, "账号未登录或授权已过期");
                        return;
                    }
                    List<QuarkFile> files = listFilesSync(folderFid);
                    QuarkFile bestCandidate = null;
                    long maxVideoSize = 0;
                    for (QuarkFile f : files) {
                        if (!f.isDir && isVideoFile(f.filename)) {
                            if (f.size > maxVideoSize) {
                                maxVideoSize = f.size;
                                bestCandidate = f;
                            }
                        }
                    }
                    if (bestCandidate != null) {
                        if (path != null) videoFileCache.put(path, bestCandidate);
                        postSuccess(callback, bestCandidate);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "findPlayableFile by fid failed, fallback to path: " + e.getMessage());
                }
                if (path != null && !path.isEmpty()) {
                    findPlayableFileByPath(path, callback);
                } else {
                    postError(callback, "未在网盘中找到可播放的视频文件");
                }
            });
        } else {
            findPlayableFileByPath(path, callback);
        }
    }

    private static boolean isVideoFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".mkv") || lower.endsWith(".mp4") ||
                lower.endsWith(".ts") || lower.endsWith(".mov") ||
                lower.endsWith(".avi") || lower.endsWith(".m2ts") ||
                lower.endsWith(".flv") || lower.endsWith(".wmv") ||
                lower.endsWith(".rmvb");
    }

    public String findCentralPosterUrl(String title) {
        if (title == null) return null;
        String t = cleanTitle(title).toLowerCase().replaceAll("[\\s·:：()（）\\[\\]0-9]", "");
        for (Map.Entry<String, String> entry : centralPosterCache.entrySet()) {
            String key = entry.getKey().replaceAll("[\\s·:：()（）\\[\\]0-9]", "");
            if (!key.isEmpty() && (t.contains(key) || key.contains(t))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String findCentralBackdropUrl(String title) {
        if (title == null) return null;
        String t = cleanTitle(title).toLowerCase().replaceAll("[\\s·:：()（）\\[\\]0-9]", "");
        for (Map.Entry<String, String> entry : centralBackdropCache.entrySet()) {
            String key = entry.getKey().replaceAll("[\\s·:：()（）\\[\\]0-9]", "");
            if (!key.isEmpty() && (t.contains(key) || key.contains(t))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static boolean isIgnoredFolderOrFile(String filename) {
        if (filename == null) return true;
        String s = filename.trim().toLowerCase();
        return s.contains("海报") || s.contains("poster") || s.contains("fanart")
                || s.contains("extra") || s.contains("花絮") || s.contains("预告")
                || s.contains("trailer") || s.contains("sample") || s.contains("字幕")
                || s.contains("subs") || s.contains("screens") || s.contains("剧照")
                || s.contains("bdmv") || s.contains("certificate");
    }

    private static boolean isCategoryName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        return n.contains("电影") || n.contains("movie") ||
                n.contains("电视剧") || n.contains("剧集") || n.contains("tv") || n.contains("series") ||
                n.contains("动漫") || n.contains("动画") || n.contains("anime") ||
                n.contains("综艺") || n.contains("variety") || n.contains("show");
    }

    private static String beautifyCategoryName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.contains("动漫") || s.contains("动画") || s.contains("anime") || s.contains("二次元")) return "动漫";
        if (s.contains("电视剧") || s.contains("连续剧") || s.contains("剧集") || s.contains("tv") || s.contains("series")) return "电视剧";
        if (s.contains("综艺") || s.contains("show") || s.contains("variety") || s.contains("真人秀")) return "综艺";
        if (s.contains("电影") || s.contains("movie") || s.contains("film") || s.contains("4k")) return "电影";
        return null;
    }

    private static String cleanTitle(String filename) {
        if (filename == null) return "";
        String title = filename.trim();
        int dot = title.lastIndexOf('.');
        if (dot > 0) {
            title = title.substring(0, dot).trim();
        }
        // 剥离如 "01. ", "02. ", "01 ", "01- ", "01、" 等数字序号前缀，展示纯净官方影片名称
        title = title.replaceFirst("^[0-9]{1,2}[.\\s、-]+", "").trim();
        return title;
    }

    private static String formatVideoMeta(long size) {
        if (size <= 0) return "4K 原画 · 夸克云盘";
        if (size >= 1024L * 1024 * 1024) {
            float gb = size / (1024f * 1024f * 1024f);
            return String.format(java.util.Locale.getDefault(), "%.1f GB · 4K 原画", gb);
        } else {
            float mb = size / (1024f * 1024f);
            return String.format(java.util.Locale.getDefault(), "%.0f MB · 超清", mb);
        }
    }

    public static int parseEpisodeNumber(String filename, int fallbackIndex) {
        if (filename == null || filename.isEmpty()) return fallbackIndex;
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("(?i)(?:s\\d{1,2})?e(?:p)?0*(\\d{1,4})");
        java.util.regex.Matcher m1 = p1.matcher(filename);
        if (m1.find()) {
            try { return Integer.parseInt(m1.group(1)); } catch (Exception ignored) {}
        }

        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("第\\s*0*(\\d{1,4})\\s*[集话期]");
        java.util.regex.Matcher m2 = p2.matcher(filename);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
        }

        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("[\\[\\(\\_\\-\\.\\s]0*(\\d{1,3})[\\]\\)\\_\\-\\.\\s]");
        java.util.regex.Matcher m3 = p3.matcher(filename);
        if (m3.find()) {
            try { return Integer.parseInt(m3.group(1)); } catch (Exception ignored) {}
        }

        return fallbackIndex;
    }

    public static String formatEpisodeDisplayName(String filename, int epNum) {
        if (filename == null) return String.format(java.util.Locale.getDefault(), "第 %d 集", epNum);
        String name = filename.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot).trim();

        // 提取如 "第08期下" / "第08期上" / "先导片01" / "EP01" 等有意义剧集/综艺期数标识
        java.util.regex.Matcher mQi = java.util.regex.Pattern.compile("(?:第)?\\s*(\\d{1,2}期(?:[上下])?)").matcher(name);
        if (mQi.find()) {
            return mQi.group(1);
        }
        java.util.regex.Matcher mXian = java.util.regex.Pattern.compile("(先导片\\s*\\d*)").matcher(name);
        if (mXian.find()) {
            return mXian.group(1);
        }
        java.util.regex.Matcher mEp = java.util.regex.Pattern.compile("第\\s*(\\d{1,3})\\s*集").matcher(name);
        if (mEp.find()) {
            return "第 " + mEp.group(1) + " 集";
        }
        return String.format(java.util.Locale.getDefault(), "%02d", epNum);
    }

    /**
     * 获取剧集列表（智能按集数排序，支持子目录正片递归穿透）
     */
    public void getEpisodes(String folderFid, String path, Callback<List<CloudEpisode>> callback) {
        executor.execute(() -> {
            try {
                if (!ensureAccessTokenValidSync()) {
                    postError(callback, "夸克账号未登录或授权已过期");
                    return;
                }

                String targetFid = folderFid;
                if (targetFid == null || targetFid.isEmpty()) {
                    if (path != null && !path.isEmpty()) {
                        targetFid = resolvePathToFid(path);
                        if (targetFid == null) {
                            String lastSeg = path;
                            int idx = path.lastIndexOf('/');
                            if (idx >= 0 && idx < path.length() - 1) lastSeg = path.substring(idx + 1).trim();
                            targetFid = findDirByFuzzyName(lastSeg);
                        }
                    }
                }

                List<CloudEpisode> episodes = new ArrayList<>();
                if (targetFid != null && !targetFid.isEmpty()) {
                    List<QuarkFile> files = listFilesSync(targetFid);
                    List<QuarkFile> targetVideos = new ArrayList<>();
                    List<QuarkFile> subDirs = new ArrayList<>();

                    for (QuarkFile f : files) {
                        if (isIgnoredFolderOrFile(f.filename)) continue;
                        if (!f.isDir && isVideoFile(f.filename)) {
                            targetVideos.add(f);
                        } else if (f.isDir) {
                            subDirs.add(f);
                        }
                    }

                    // 如果当前目录没有直接视频文件，递归探测子目录（如：喜人奇妙夜/第二季/正片）
                    if (targetVideos.isEmpty() && !subDirs.isEmpty()) {
                        QuarkFile zhengpianDir = null;
                        for (QuarkFile sd : subDirs) {
                            if (sd.filename.contains("正片") || sd.filename.contains("视频")) {
                                zhengpianDir = sd;
                                break;
                            }
                        }
                        if (zhengpianDir != null) {
                            List<QuarkFile> subFiles = listFilesSync(zhengpianDir.fid);
                            for (QuarkFile sf : subFiles) {
                                if (!sf.isDir && isVideoFile(sf.filename)) {
                                    targetVideos.add(sf);
                                }
                            }
                        } else {
                            for (QuarkFile sd : subDirs) {
                                List<QuarkFile> subFiles = listFilesSync(sd.fid);
                                for (QuarkFile sf : subFiles) {
                                    if (!sf.isDir && isVideoFile(sf.filename)) {
                                        targetVideos.add(sf);
                                    }
                                }
                                if (!targetVideos.isEmpty()) break;
                            }
                        }
                    }

                    // 自然排序
                    Collections.sort(targetVideos, (a, b) -> a.filename.compareTo(b.filename));

                    int fallbackIdx = 1;
                    for (QuarkFile f : targetVideos) {
                        int epNum = parseEpisodeNumber(f.filename, fallbackIdx++);
                        String epName = formatEpisodeDisplayName(f.filename, epNum);
                        episodes.add(new CloudEpisode(epNum, epName, f.filename, f.fid, f.size));
                    }
                    Collections.sort(episodes, (a, b) -> Integer.compare(a.episodeNumber, b.episodeNumber));
                }

                postSuccess(callback, episodes);
            } catch (Exception e) {
                Log.e(TAG, "getEpisodes failed", e);
                postError(callback, "获取剧集列表失败：" + e.getMessage());
            }
        });
    }

    /**
     * 根据路径解析并查找第一个可播放的视频文件
     * 路径示例："/来自：分享/电影/时空恋旅人"
     */
    public void findPlayableFileByPath(String path, Callback<QuarkFile> callback) {
        executor.execute(() -> {
            try {
                if (!ensureAccessTokenValidSync()) {
                    postError(callback, "账号未登录或授权已过期，请扫码登录");
                    return;
                }

                if (videoFileCache.containsKey(path)) {
                    postSuccess(callback, videoFileCache.get(path));
                    return;
                }

                String targetDirFid = resolvePathToFid(path);
                if (targetDirFid == null) {
                    // 容错搜索：尝试在网盘根目录或一级子目录中按文件夹名称模糊检索
                    String lastSegment = path;
                    int lastSlash = path.lastIndexOf('/');
                    if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                        lastSegment = path.substring(lastSlash + 1).trim();
                    }
                    targetDirFid = findDirByFuzzyName(lastSegment);
                }

                if (targetDirFid == null) {
                    postError(callback, "未在网盘中找到对应文件夹：" + path);
                    return;
                }

                // 获取目标文件夹内的全部文件
                String pathname = "/file";
                Map<String, String> query = new HashMap<>();
                query.put("method", "list");
                query.put("parent_fid", targetDirFid);
                query.put("page_size", "100");
                query.put("page_index", "0");

                String respStr = executeOpenApi("GET", pathname, query, null, true);
                JSONObject json = new JSONObject(respStr);
                JSONObject data = json.optJSONObject("data");

                QuarkFile bestCandidate = null;
                long maxVideoSize = 0;

                if (data != null && data.has("files")) {
                    JSONArray arr = data.getJSONArray("files");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject f = arr.getJSONObject(i);
                        boolean isDir = f.optInt("isdir") == 1;
                        if (isDir) continue;

                        String fname = f.optString("filename", "").toLowerCase();
                        long size = f.optLong("size", 0);

                        // 优先匹配主流高清格式
                        boolean isVideo = fname.endsWith(".mkv") || fname.endsWith(".mp4") ||
                                fname.endsWith(".ts") || fname.endsWith(".mov") ||
                                fname.endsWith(".avi") || fname.endsWith(".m2ts") ||
                                fname.endsWith(".flv");

                        if (isVideo && size > maxVideoSize) {
                            maxVideoSize = size;
                            bestCandidate = new QuarkFile(
                                    f.optString("fid"),
                                    f.optString("parent_fid"),
                                    f.optString("filename"),
                                    size,
                                    false,
                                    f.optString("file_type"),
                                    f.optString("thumbnail_url")
                            );
                        }
                    }
                }

                if (bestCandidate != null) {
                    videoFileCache.put(path, bestCandidate);
                    postSuccess(callback, bestCandidate);
                } else {
                    postError(callback, "文件夹内未找到可播放的视频文件");
                }
            } catch (Exception e) {
                Log.e(TAG, "findPlayableFileByPath error", e);
                postError(callback, "查找影视文件失败：" + e.getMessage());
            }
        });
    }

    private String resolvePathToFid(String fullPath) throws Exception {
        if (dirFidCache.containsKey(fullPath)) {
            return dirFidCache.get(fullPath);
        }

        String[] parts = fullPath.split("/");
        String currentFid = "0";

        for (String segment : parts) {
            String seg = segment.trim();
            if (seg.isEmpty()) continue;

            // 查找 currentFid 下名为 seg 的子文件夹
            Map<String, String> query = new HashMap<>();
            query.put("method", "list");
            query.put("parent_fid", currentFid);
            query.put("page_size", "100");
            query.put("page_index", "0");

            String respStr = executeOpenApi("GET", "/file", query, null, true);
            JSONObject json = new JSONObject(respStr);
            JSONObject data = json.optJSONObject("data");
            String foundFid = null;

            if (data != null && data.has("files")) {
                JSONArray arr = data.getJSONArray("files");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.getJSONObject(i);
                    if (f.optInt("isdir") == 1 && seg.equalsIgnoreCase(f.optString("filename"))) {
                        foundFid = f.optString("fid");
                        break;
                    }
                }
            }

            if (foundFid == null) {
                return null;
            }
            currentFid = foundFid;
        }

        dirFidCache.put(fullPath, currentFid);
        return currentFid;
    }

    private String findDirByFuzzyName(String targetName) {
        if (targetName == null || targetName.isEmpty()) return null;
        try {
            // 1. 扫描根目录所有子目录
            Map<String, String> query = new HashMap<>();
            query.put("method", "list");
            query.put("parent_fid", "0");
            query.put("page_size", "100");
            query.put("page_index", "0");

            String respStr = executeOpenApi("GET", "/file", query, null, true);
            JSONObject json = new JSONObject(respStr);
            JSONObject data = json.optJSONObject("data");
            if (data == null || !data.has("files")) return null;

            JSONArray arr = data.getJSONArray("files");
            List<String> subDirFids = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.getJSONObject(i);
                if (f.optInt("isdir") == 1) {
                    String name = f.optString("filename");
                    if (name.equalsIgnoreCase(targetName) || name.contains(targetName) || targetName.contains(name)) {
                        return f.optString("fid");
                    }
                    subDirFids.add(f.optString("fid"));
                }
            }

            // 2. 深度 1：扫描常用分享或分类文件夹内的子目录
            for (String subFid : subDirFids) {
                query.put("parent_fid", subFid);
                String subResp = executeOpenApi("GET", "/file", query, null, true);
                JSONObject subJson = new JSONObject(subResp);
                JSONObject subData = subJson.optJSONObject("data");
                if (subData != null && subData.has("files")) {
                    JSONArray subArr = subData.getJSONArray("files");
                    for (int j = 0; j < subArr.length(); j++) {
                        JSONObject sf = subArr.getJSONObject(j);
                        if (sf.optInt("isdir") == 1) {
                            String name = sf.optString("filename");
                            if (name.equalsIgnoreCase(targetName) || name.contains(targetName) || targetName.contains(name)) {
                                return sf.optString("fid");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findDirByFuzzyName error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取 4K / 杜比视界官方直链
     */
    public void getPlaybackUrl(String fid, Callback<String> callback) {
        executor.execute(() -> {
            try {
                if (!ensureAccessTokenValidSync()) {
                    postError(callback, "账号未登录或登录已过期，请重新扫码");
                    return;
                }

                String pathname = "/file";
                Map<String, String> query = new HashMap<>();
                query.put("method", "download");
                query.put("group_by", "source");
                query.put("fid", fid);
                query.put("resolution", "low,normal,high,super,2k,4k");
                query.put("support", "dolby_vision");

                String respStr = executeOpenApi("GET", pathname, query, null, true);
                Log.i(TAG, "getPlaybackUrl API response: " + respStr);
                JSONObject json = new JSONObject(respStr);
                JSONObject data = json.optJSONObject("data");

                if (data != null) {
                    if (data.has("download_url")) {
                        String dlUrl = data.getString("download_url");
                        if (!dlUrl.isEmpty()) {
                            postSuccess(callback, dlUrl);
                            return;
                        }
                    }
                    if (data.has("backup_download_url")) {
                        String bUrl = data.getString("backup_download_url");
                        if (!bUrl.isEmpty()) {
                            postSuccess(callback, bUrl);
                            return;
                        }
                    }
                }
                postError(callback, "未获取到有效播放直链：" + json.optString("error_info", "未知原因"));
            } catch (Exception e) {
                Log.e(TAG, "getPlaybackUrl failed", e);
                postError(callback, "获取播放直链异常：" + e.getMessage());
            }
        });
    }

    private String executeOpenApi(String method, String pathname, Map<String, String> extraQuery, JSONObject body, boolean needAuth) throws Exception {
        String deviceId = ensureDeviceId();
        String timestamp = String.valueOf(System.currentTimeMillis());

        // 1. 生成 x_pan_token
        String tokenData = method + "&" + pathname + "&" + timestamp + "&" + SIGN_KEY;
        String xPanToken = sha256(tokenData);

        // 2. 生成 req_id
        String reqId = md5(deviceId + timestamp);

        // 3. 组装 Query 参数
        Map<String, String> query = new HashMap<>();
        query.put("req_id", reqId);
        query.put("app_ver", APP_VER);
        query.put("device_id", deviceId);
        query.put("device_brand", DEVICE_BRAND);
        query.put("platform", PLATFORM);
        query.put("device_name", DEVICE_NAME);
        query.put("device_model", DEVICE_MODEL);
        query.put("build_device", BUILD_DEVICE);
        query.put("build_product", BUILD_PRODUCT);
        query.put("device_gpu", DEVICE_GPU);
        query.put("activity_rect", ACTIVITY_RECT);
        query.put("channel", CHANNEL);

        if (needAuth) {
            String accessToken = prefs.getString(KEY_ACCESS_TOKEN, "");
            query.put("access_token", accessToken);
        }

        if (extraQuery != null) {
            query.putAll(extraQuery);
        }

        StringBuilder sb = new StringBuilder(OPEN_API_HOST).append(pathname).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) sb.append("&");
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);

        // 装配官方防盗链与鉴权头
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-pan-tm", timestamp);
        conn.setRequestProperty("x-pan-token", xPanToken);
        conn.setRequestProperty("x-pan-client-id", CLIENT_ID);

        if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readString(is);

        // A remotely removed device can still have locally unexpired credentials.
        // Device-limit and transport failures must not discard a valid login.
        if (needAuth && !resp.isEmpty()) {
            try {
                if (new JSONObject(resp).optInt("errno", -1) == 11001) {
                    logout();
                    throw new IllegalStateException("登录已失效或设备已被移除，请重新扫码登录");
                }
            } catch (org.json.JSONException ignored) {
                // Preserve the normal HTTP error for non-JSON responses.
            }
        }
        if (code >= 400) {
            throw new HttpException(code, resp);
        }
        return resp;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readString(InputStream is) throws Exception {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private <T> void postSuccess(Callback<T> callback, T result) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(result));
        }
    }

    private <T> void postError(Callback<T> callback, String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    private static class HttpException extends Exception {
        final int code;
        final String body;

        HttpException(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
            this.body = body;
        }
    }
}
