package com.gathin.tvhome.player;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 电视端夸克网盘原画专属分片并发数据源 (QuarkChunkedDataSource)
 * 核心原理：
 * 1. 突破单连接限速：夸克对 Range: bytes=0- 长连接实施 ~1.5MB/s 限速，
 *    通过闭区间 Range: bytes=A-B (4MB) 伪装普通分块下载，单连接即可拉满带宽。
 * 2. 滑动窗口 3 线程并发预取：内存驻留最多 4 个 4MB Chunk (16MB)，杜绝 TV OOM。
 * 3. 极速元数据探测直通：MP4 moov / MKV Cues 小请求直接单连瞬发，不触发无谓预取，1秒出解析。
 * 4. 零 Socket 拷贝：纯在 ExoPlayer DataSource 内存管道内组装，消除 Local HTTP Proxy 的回环延迟与端口冲突。
 */
public class QuarkChunkedDataSource extends BaseDataSource {
    private static final String TAG = "QuarkChunkedDS";

    public static final long CHUNK_SIZE = 4 * 1024 * 1024; // 4MB
    public static final int PARALLEL_CONCURRENCY = 3;      // 3 线程并发预取
    public static final int MAX_PREFETCH_CHUNKS = 4;       // 内存最多 16MB

    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(8, 2, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final ExecutorService PREFETCH_POOL = Executors.newFixedThreadPool(6);

    private final String userAgent;
    private DataSpec dataSpec;
    private Uri uri;
    private String urlString;

    private boolean isOpened = false;
    private long totalContentLength = C.LENGTH_UNSET;
    private long bytesRemaining = C.LENGTH_UNSET;
    private long currentFilePosition = 0;

    // 是否为元数据短探测请求（走直连模式）
    private boolean isDirectMode = false;
    private InputStream directInputStream = null;
    private Response directResponse = null;

    // 分片模式状态机
    private long currentChunkIndex = 0;
    private byte[] currentChunkBuffer = null;
    private int currentChunkOffset = 0;
    private int currentChunkValidBytes = 0;

    private final Map<Long, Future<ChunkResult>> prefetchTasks = new ConcurrentHashMap<>();
    private final Map<Long, ChunkResult> completedChunks = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    public static class Factory implements DataSource.Factory {
        private final String userAgent;

        public Factory(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override
        public DataSource createDataSource() {
            return new QuarkChunkedDataSource(userAgent);
        }
    }

    public QuarkChunkedDataSource(String userAgent) {
        super(/* isNetwork= */ true);
        this.userAgent = userAgent;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        this.uri = dataSpec.uri;
        this.urlString = uri.toString();
        this.isClosed.set(false);
        this.currentFilePosition = dataSpec.position;

        transferInitializing(dataSpec);

        long reqPos = dataSpec.position;
        long reqLen = dataSpec.length;

        // 策略 A：小范围元数据探测请求（例如 MP4 moov 探测或尾部定位，小于 2MB），走直通单连模式
        if (reqLen != C.LENGTH_UNSET && reqLen <= (CHUNK_SIZE / 2)) {
            isDirectMode = true;
            return openDirect(reqPos, reqLen);
        }

        // 策略 B & C：连续媒体流，进入 4MB 闭区间滑动窗口分片并发加速模式
        isDirectMode = false;
        return openChunked(reqPos, reqLen);
    }

    private long openDirect(long position, long length) throws IOException {
        long endPos = position + length - 1;
        String rangeHeader = "bytes=" + position + "-" + endPos;

        Request request = new Request.Builder()
                .url(urlString)
                .header("User-Agent", userAgent)
                .header("Range", rangeHeader)
                .build();

        Response response = SHARED_CLIENT.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new HttpDataSource.InvalidResponseCodeException(response.code(), response.message(), null, Collections.emptyMap(), dataSpec, new byte[0]);
        }

        directResponse = response;
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new IOException("Empty response body for direct probe");
        }

        directInputStream = body.byteStream();
        bytesRemaining = length;
        isOpened = true;

        transferStarted(dataSpec);
        return bytesRemaining;
    }

    private long openChunked(long startPos, long reqLength) throws IOException {
        currentChunkIndex = startPos / CHUNK_SIZE;
        long chunkStart = currentChunkIndex * CHUNK_SIZE;
        long chunkEnd = chunkStart + CHUNK_SIZE - 1;

        // 同步下载首个分片，获取文件真实总大小并填装首块数据
        ChunkResult firstChunk = fetchChunkSync(currentChunkIndex, chunkStart, chunkEnd);
        if (firstChunk == null || firstChunk.data == null) {
            throw new IOException("Failed to load initial chunk: " + currentChunkIndex);
        }

        totalContentLength = firstChunk.totalFileSize;
        currentChunkBuffer = firstChunk.data;
        currentChunkValidBytes = firstChunk.length;
        currentChunkOffset = (int) (startPos - chunkStart);

        if (reqLength != C.LENGTH_UNSET) {
            bytesRemaining = reqLength;
        } else if (totalContentLength != C.LENGTH_UNSET) {
            bytesRemaining = totalContentLength - startPos;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }

        // 启动后台 3 线程并发预取后续分片 (Chunk[N+1], Chunk[N+2], Chunk[N+3])
        schedulePrefetch(currentChunkIndex + 1);

        isOpened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    private void schedulePrefetch(long fromIndex) {
        if (isClosed.get() || totalContentLength <= 0) return;

        long maxChunkIndex = (totalContentLength + CHUNK_SIZE - 1) / CHUNK_SIZE;

        for (int i = 0; i < PARALLEL_CONCURRENCY; i++) {
            long targetIndex = fromIndex + i;
            if (targetIndex >= maxChunkIndex) break;

            if (!completedChunks.containsKey(targetIndex) && !prefetchTasks.containsKey(targetIndex)) {
                if (prefetchTasks.size() >= MAX_PREFETCH_CHUNKS) break;

                long cStart = targetIndex * CHUNK_SIZE;
                long cEnd = Math.min(totalContentLength - 1, cStart + CHUNK_SIZE - 1);

                Future<ChunkResult> future = PREFETCH_POOL.submit(new Callable<ChunkResult>() {
                    @Override
                    public ChunkResult call() {
                        if (isClosed.get()) return null;
                        try {
                            return fetchChunkSync(targetIndex, cStart, cEnd);
                        } catch (Exception e) {
                            Log.w(TAG, "Prefetch chunk failed for index " + targetIndex + ": " + e.getMessage());
                            return null;
                        }
                    }
                });
                prefetchTasks.put(targetIndex, future);
            }
        }
    }

    private ChunkResult fetchChunkSync(long index, long startByte, long endByte) throws IOException {
        if (isClosed.get()) return null;

        String rangeHeader = "bytes=" + startByte + "-" + endByte;
        Request request = new Request.Builder()
                .url(urlString)
                .header("User-Agent", userAgent)
                .header("Range", rangeHeader)
                .build();

        try (Response response = SHARED_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 206) {
                throw new IOException("HTTP " + response.code() + " loading chunk " + index);
            }

            ResponseBody body = response.body();
            if (body == null) throw new IOException("Null body for chunk " + index);

            long total = parseTotalLengthFromContentRange(response.header("Content-Range"));
            if (total <= 0) {
                total = body.contentLength() > 0 ? (startByte + body.contentLength()) : C.LENGTH_UNSET;
            }

            byte[] bytes = body.bytes();
            return new ChunkResult(index, bytes, bytes.length, total);
        }
    }

    private long parseTotalLengthFromContentRange(String contentRange) {
        if (contentRange == null) return C.LENGTH_UNSET;
        try {
            int slashIndex = contentRange.lastIndexOf('/');
            if (slashIndex >= 0 && slashIndex < contentRange.length() - 1) {
                return Long.parseLong(contentRange.substring(slashIndex + 1).trim());
            }
        } catch (Exception ignored) {}
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

        if (isDirectMode) {
            int bytesToRead = (int) (bytesRemaining == C.LENGTH_UNSET ? length : Math.min(bytesRemaining, length));
            int read = directInputStream.read(buffer, offset, bytesToRead);
            if (read == -1) {
                return C.RESULT_END_OF_INPUT;
            }
            if (bytesRemaining != C.LENGTH_UNSET) {
                bytesRemaining -= read;
            }
            bytesTransferred(read);
            return read;
        }

        // 分片滑动窗口消费
        if (currentChunkBuffer == null || currentChunkOffset >= currentChunkValidBytes) {
            // 当前分片已读完，步进至下一分片
            currentChunkIndex++;
            ChunkResult nextChunk = getOrAwaitChunk(currentChunkIndex);
            if (nextChunk == null || nextChunk.data == null || nextChunk.length == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            currentChunkBuffer = nextChunk.data;
            currentChunkValidBytes = nextChunk.length;
            currentChunkOffset = 0;

            // 推进预取窗口
            schedulePrefetch(currentChunkIndex + 1);
        }

        int availableInChunk = currentChunkValidBytes - currentChunkOffset;
        int bytesToRead = Math.min(length, availableInChunk);
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesToRead = (int) Math.min(bytesToRead, bytesRemaining);
        }

        System.arraycopy(currentChunkBuffer, currentChunkOffset, buffer, offset, bytesToRead);
        currentChunkOffset += bytesToRead;
        currentFilePosition += bytesToRead;

        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesToRead;
        }

        bytesTransferred(bytesToRead);
        return bytesToRead;
    }

    private ChunkResult getOrAwaitChunk(long index) throws IOException {
        if (isClosed.get()) return null;

        // 1. 已在已完成缓存中
        ChunkResult ready = completedChunks.remove(index);
        if (ready != null) return ready;

        // 2. 正在预取任务中
        Future<ChunkResult> future = prefetchTasks.remove(index);
        if (future != null) {
            try {
                ChunkResult res = future.get(12, TimeUnit.SECONDS);
                if (res != null) return res;
            } catch (Exception e) {
                Log.w(TAG, "Await chunk " + index + " failed: " + e.getMessage());
            }
        }

        // 3. 尚未预取或预取异常，同步兜底拉取
        long cStart = index * CHUNK_SIZE;
        long cEnd = (totalContentLength > 0) ? Math.min(totalContentLength - 1, cStart + CHUNK_SIZE - 1) : cStart + CHUNK_SIZE - 1;
        return fetchChunkSync(index, cStart, cEnd);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        isClosed.set(true);

        // 瞬间取消所有后台分片拉取，不偷跑任何带宽
        for (Future<ChunkResult> future : prefetchTasks.values()) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        prefetchTasks.clear();
        completedChunks.clear();
        currentChunkBuffer = null;

        if (directInputStream != null) {
            try { directInputStream.close(); } catch (Exception ignored) {}
            directInputStream = null;
        }
        if (directResponse != null) {
            try { directResponse.close(); } catch (Exception ignored) {}
            directResponse = null;
        }

        if (isOpened) {
            isOpened = false;
            transferEnded();
        }
    }

    private static class ChunkResult {
        final long index;
        final byte[] data;
        final int length;
        final long totalFileSize;

        ChunkResult(long index, byte[] data, int length, long totalFileSize) {
            this.index = index;
            this.data = data;
            this.length = length;
            this.totalFileSize = totalFileSize;
        }
    }
}
