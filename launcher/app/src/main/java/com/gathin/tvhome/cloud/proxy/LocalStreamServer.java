package com.gathin.tvhome.cloud.proxy;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电视端嵌入式微型流媒体中继代理 (LocalStreamServer)
 * 作用：
 * 1. 拦截电视硬件播放器 (com.mitv.videoplayer) 发起的 HTTP Range 请求；
 * 2. 伪装 TV 专属请求头 (User-Agent / Referer / Range) 代理请求网盘 CDN；
 * 3. 避免跨域与防盗链拦截，平滑切片缓冲，杜绝拖动进度条风控卡死。
 */
public class LocalStreamServer {
    private static final String TAG = "LocalStreamServer";
    private static final int DEFAULT_PORT = 9978;
    private static final String TV_USER_AGENT = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1";

    private static volatile LocalStreamServer instance;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private int boundPort = DEFAULT_PORT;
    private ExecutorService connectionPool;

    private LocalStreamServer() {}

    public static LocalStreamServer getInstance() {
        if (instance == null) {
            synchronized (LocalStreamServer.class) {
                if (instance == null) {
                    instance = new LocalStreamServer();
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        if (isRunning.get()) return;

        connectionPool = Executors.newCachedThreadPool();
        try {
            // 优先尝试标准端口 9978，若被占用则绑定系统分配的空闲端口
            try {
                serverSocket = new ServerSocket(DEFAULT_PORT, 50, InetAddress.getByName("127.0.0.1"));
                boundPort = DEFAULT_PORT;
            } catch (Exception e) {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                boundPort = serverSocket.getLocalPort();
            }

            isRunning.set(true);
            Log.i(TAG, "LocalStreamServer started on 127.0.0.1:" + boundPort);

            connectionPool.execute(this::acceptLoop);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start LocalStreamServer", e);
        }
    }

    public synchronized void stop() {
        if (!isRunning.get()) return;
        isRunning.set(false);

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        if (connectionPool != null) {
            connectionPool.shutdownNow();
        }
        Log.i(TAG, "LocalStreamServer stopped");
    }

    public int getPort() {
        return boundPort;
    }

    /**
     * 将远端视频直链包装成本地代理播放地址
     */
    public String getStreamUrl(String remoteCdnUrl) {
        if (!isRunning.get()) {
            start();
        }
        try {
            String encoded = URLEncoder.encode(remoteCdnUrl, "UTF-8");
            return "http://127.0.0.1:" + boundPort + "/stream?url=" + encoded;
        } catch (Exception e) {
            return remoteCdnUrl;
        }
    }

    private void acceptLoop() {
        while (isRunning.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionPool.execute(() -> handleClient(clientSocket));
            } catch (Exception e) {
                if (!isRunning.get()) break;
                Log.w(TAG, "accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        HttpURLConnection upstreamConn = null;
        try {
            clientSocket.setSoTimeout(30000);
            clientSocket.setSendBufferSize(262144);
            clientSocket.setReceiveBufferSize(262144);
            clientSocket.setTcpNoDelay(true);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = new BufferedOutputStream(clientSocket.getOutputStream(), 262144);

            // 1. 读取 HTTP 请求首行与头部
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) {
                clientSocket.close();
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) {
                clientSocket.close();
                return;
            }

            String method = parts[0].toUpperCase();
            String uri = parts[1];
            if (uri.startsWith("/status")) {
                byte[] resp = "{\"status\":\"running\"}\r\n".getBytes(StandardCharsets.UTF_8);
                clientOut.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ".getBytes(StandardCharsets.UTF_8));
                clientOut.write(String.valueOf(resp.length).getBytes(StandardCharsets.UTF_8));
                clientOut.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                if (!"HEAD".equals(method)) {
                    clientOut.write(resp);
                }
                clientOut.flush();
                clientSocket.close();
                return;
            }

            if (!uri.startsWith("/stream")) {
                clientOut.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                clientOut.flush();
                clientSocket.close();
                return;
            }

            // 2. 解析目标直链 URL
            int qIdx = uri.indexOf("url=");
            if (qIdx < 0) {
                clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                clientOut.flush();
                clientSocket.close();
                return;
            }

            String rawUrl = uri.substring(qIdx + 4);
            int ampIdx = rawUrl.indexOf('&');
            if (ampIdx > 0) rawUrl = rawUrl.substring(0, ampIdx);
            String upstreamUrl = URLDecoder.decode(rawUrl, "UTF-8");

            // 3. 建立至上游 CDN 的连接（支持跟随跨协议重定向），装配专用防盗链与 Range 请求头
            int redirectCount = 0;
            while (redirectCount < 5) {
                upstreamConn = (HttpURLConnection) new URL(upstreamUrl).openConnection();
                upstreamConn.setRequestMethod(method.equals("HEAD") ? "HEAD" : "GET");
                upstreamConn.setConnectTimeout(10000);
                upstreamConn.setReadTimeout(30000);
                upstreamConn.setInstanceFollowRedirects(false);

                upstreamConn.setRequestProperty("User-Agent", TV_USER_AGENT);
                upstreamConn.setRequestProperty("Accept", "*/*");

                if (headers.containsKey("range")) {
                    upstreamConn.setRequestProperty("Range", headers.get("range"));
                }

                int code = upstreamConn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = upstreamConn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        upstreamUrl = loc;
                        upstreamConn.disconnect();
                        redirectCount++;
                        continue;
                    }
                }
                break;
            }

            int upstreamCode = upstreamConn.getResponseCode();
            String statusLine = (upstreamCode == 206) ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
            clientOut.write(statusLine.getBytes(StandardCharsets.UTF_8));

            // 透传核心媒体标头
            String contentType = upstreamConn.getHeaderField("Content-Type");
            if (contentType == null || contentType.isEmpty()) contentType = "video/mp4";
            clientOut.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));

            String contentRange = upstreamConn.getHeaderField("Content-Range");
            if (contentRange != null) {
                clientOut.write(("Content-Range: " + contentRange + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            long contentLength = upstreamConn.getContentLengthLong();
            if (contentLength > 0) {
                clientOut.write(("Content-Length: " + contentLength + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            clientOut.write("Accept-Ranges: bytes\r\n".getBytes(StandardCharsets.UTF_8));
            clientOut.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            clientOut.flush();

            // 4. 若为 HEAD 请求，传输完头部即结束；若为 GET 则高速无损流式推流给本地电视播放器（256KB 缓冲区）
            if (!"HEAD".equals(method)) {
                InputStream upstreamIn = new BufferedInputStream(upstreamConn.getInputStream(), 262144);
                byte[] buf = new byte[262144];
                int read;
                while ((read = upstreamIn.read(buf)) != -1) {
                    clientOut.write(buf, 0, read);
                }
                clientOut.flush();
            }

        } catch (SocketException e) {
            // 播放器拖动进度条、Seek 或退出时触发，属于正常流断开
        } catch (Exception e) {
            Log.w(TAG, "stream relay interrupted: " + e.getMessage());
        } finally {
            if (upstreamConn != null) {
                try { upstreamConn.disconnect(); } catch (Exception ignored) {}
            }
            try { clientSocket.close(); } catch (Exception ignored) {}
        }
    }
}
