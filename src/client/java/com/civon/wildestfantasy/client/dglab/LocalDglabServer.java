package com.civon.wildestfantasy.client.dglab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内嵌的极简 WebSocket 服务端，让 DG-LAB APP 直连本机，不经过官方中转。
 *
 * <p>和「连官方中转」的区别只在于传输层：协议帧完全一样（V4），
 * 只是原本由中转服务做的握手/心跳改由本类承担。
 *
 * <pre>
 *   DG-LAB APP --ws://局域网IP:端口?tid=配对码--&gt; 本类（本模组）
 * </pre>
 *
 * <p>只实现必需的部分：握手、文本帧、ping/pong、close、分片重组。
 * 不引入任何第三方库（SHA-1 与 Base64 都是 JDK 自带）。
 */
public final class LocalDglabServer {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/localws");

    /** RFC 6455 规定的握手 GUID。 */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** 端口被占用时往后尝试的个数。 */
    private static final int PORT_ATTEMPTS = 10;
    /** 心跳间隔，和官方中转一致（25 秒）。 */
    private static final long HEARTBEAT_MS = 25_000L;
    private static final int MAX_HEADER_BYTES = 8192;

    /** 服务器事件回调，全部在连接线程上触发。 */
    public interface Listener {
        /** APP 已完成握手并接入。 */
        void onAttached(String appClientId);

        /** 收到一条文本帧（已是完整 JSON 字符串）。 */
        void onText(String json);

        /** 连接断开（主动 stop 时也会触发一次）。 */
        void onDetached();
    }

    private final int requestedPort;
    private final String pairingCode;
    private final Listener listener;
    private final String lanIp;

    private volatile ServerSocket serverSocket;
    private volatile int actualPort;
    private volatile ClientConnection current;
    private volatile boolean running;
    private Thread boss;
    private Thread heartbeat;

    public LocalDglabServer(int port, String pairingCode, Listener listener) {
        this.requestedPort = port <= 0 ? 9977 : port;
        this.pairingCode = pairingCode;
        this.listener = listener;
        this.lanIp = guessLanIp();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 开始监听。端口被占用时自动往后找一个可用的。
     *
     * @return 实际监听的端口；失败返回 -1
     */
    public synchronized int start() {
        if (running) {
            return actualPort;
        }
        ServerSocket socket = null;
        int bound = -1;
        for (int i = 0; i < PORT_ATTEMPTS; i++) {
            int candidate = requestedPort + i;
            if (candidate > 65535) {
                break;
            }
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(candidate));
                socket = s;
                bound = candidate;
                break;
            } catch (IOException e) {
                LOG.debug("端口 {} 不可用: {}", candidate, e.toString());
            }
        }
        if (socket == null) {
            LOG.warn("本地服务器无法绑定 {}-{} 之间的任何端口", requestedPort, requestedPort + PORT_ATTEMPTS - 1);
            return -1;
        }

        this.serverSocket = socket;
        this.actualPort = bound;
        this.running = true;

        boss = new Thread(this::acceptLoop, "wildestfantasy-ws-accept");
        boss.setDaemon(true);
        boss.start();

        heartbeat = new Thread(this::heartbeatLoop, "wildestfantasy-ws-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        LOG.info("本地服务器已监听 0.0.0.0:{}，配对地址 {}（配对码 {}）", bound, pairingUrl(), pairingCode);
        return bound;
    }

    public synchronized void stop() {
        running = false;
        ClientConnection conn = current;
        current = null;
        if (conn != null) {
            conn.close(4000, "controller_disconnected");
        }
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭时的异常没有意义
            }
        }
        if (boss != null) {
            boss.interrupt();
            boss = null;
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
            heartbeat = null;
        }
        if (conn != null) {
            safeDetached();
        }
    }

    private void safeDetached() {
        try {
            listener.onDetached();
        } catch (Throwable t) {
            LOG.warn("onDetached 回调异常", t);
        }
    }

    // ------------------------------------------------------------------
    // 接受连接
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = serverSocket;
            if (socket == null) {
                return;
            }
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) {
                    LOG.debug("accept 结束: {}", e.toString());
                }
                return;
            }
            Thread worker = new Thread(() -> handle(client), "wildestfantasy-ws-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket client) {
        ClientConnection conn = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String header = readHttpHeader(in);
            if (header == null) {
                closeQuietly(client);
                return;
            }
            String path = parseRequestPath(header);
            String key = parseHeader(header, "sec-websocket-key");
            String upgrade = parseHeader(header, "upgrade");
            if (key == null || upgrade == null || !upgrade.toLowerCase(Locale.ROOT).equals("websocket")) {
                writeHttp(out, "400 Bad Request");
                closeQuietly(client);
                return;
            }

            // 配对码不对就拒绝，避免局域网里别的 APP 误连
            String tid = queryParam(path, "tid");
            if (tid != null && !tid.isEmpty() && !tid.equalsIgnoreCase(pairingCode)) {
                writeHttp(out, "403 Forbidden");
                LOG.warn("有连接用错配对码（{}），已拒绝", tid);
                closeQuietly(client);
                return;
            }

            String accept = acceptKey(key);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            conn = new ClientConnection(client, in, out);

            // 只保留一个连接：新连上的顶掉旧的
            ClientConnection previous = current;
            current = conn;
            if (previous != null && previous != conn) {
                previous.close(4000, "controller_disconnected");
            }

            String appId = "app-" + randomHex(8);
            LOG.info("APP 已接入（{}），来自 {}", appId, client.getRemoteSocketAddress());
            try {
                listener.onAttached(appId);
            } catch (Throwable t) {
                LOG.warn("onAttached 回调异常", t);
            }

            readLoop(conn);
        } catch (IOException e) {
            LOG.debug("连接处理结束: {}", e.toString());
        } catch (Throwable t) {
            LOG.warn("连接处理异常", t);
        } finally {
            if (conn != null) {
                conn.close(1000, "");
                if (current == conn) {
                    current = null;
                    safeDetached();
                }
            } else {
                closeQuietly(client);
            }
        }
    }

    private void readLoop(ClientConnection conn) throws IOException {
        while (!conn.closed && running) {
            int b1 = conn.in.read();
            if (b1 < 0) {
                return;
            }
            int b2 = conn.in.read();
            if (b2 < 0) {
                return;
            }
            boolean fin = (b1 & 0x80) != 0;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long length = b2 & 0x7F;
            if (length == 126) {
                length = readUnsigned(conn.in, 2);
            } else if (length == 127) {
                length = readUnsigned(conn.in, 8);
            }
            if (length < 0 || length > 8L * 1024 * 1024) {
                LOG.warn("帧长度异常（{}），断开", length);
                return;
            }
            byte[] mask = null;
            if (masked) {
                mask = readFully(conn.in, 4);
            }
            byte[] payload = length == 0 ? new byte[0] : readFully(conn.in, (int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            switch (opcode) {
                case 0x1, 0x2 -> {
                    if (!fin) {
                        // 分片：把后续的续帧拼起来
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        buffer.write(payload);
                        while (true) {
                            int c1 = conn.in.read();
                            int c2 = conn.in.read();
                            if (c1 < 0 || c2 < 0) {
                                return;
                            }
                            int cOpcode = c1 & 0x0F;
                            boolean cMasked = (c2 & 0x80) != 0;
                            long cLen = c2 & 0x7F;
                            if (cLen == 126) {
                                cLen = readUnsigned(conn.in, 2);
                            } else if (cLen == 127) {
                                cLen = readUnsigned(conn.in, 8);
                            }
                            byte[] cMask = cMasked ? readFully(conn.in, 4) : null;
                            byte[] chunk = cLen == 0 ? new byte[0] : readFully(conn.in, (int) cLen);
                            if (cMask != null) {
                                for (int i = 0; i < chunk.length; i++) {
                                    chunk[i] ^= cMask[i % 4];
                                }
                            }
                            buffer.write(chunk);
                            if (cOpcode != 0) {
                                break;
                            }
                        }
                        payload = buffer.toByteArray();
                    }
                    String text = new String(payload, StandardCharsets.UTF_8);
                    try {
                        listener.onText(text);
                    } catch (Throwable t) {
                        LOG.warn("onText 回调异常", t);
                    }
                }
                case 0x8 -> {
                    conn.closed = true;
                    return;
                }
                case 0x9 -> conn.sendFrame(0xA, payload);   // ping -> pong
                case 0xA -> {
                    // pong，无需处理
                }
                default -> {
                    // 其它控制帧忽略
                }
            }
        }
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException e) {
                return;
            }
            ClientConnection conn = current;
            if (conn == null || conn.closed) {
                continue;
            }
            conn.sendText("{\"type\":\"heartbeat\"}");
            conn.sendFrame(0x9, new byte[0]);
        }
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 局域网地址，手机要连这个（不能是 127.0.0.1）。 */
    public String lanIp() {
        return lanIp;
    }

    public int port() {
        return actualPort > 0 ? actualPort : requestedPort;
    }

    public String pairingCode() {
        return pairingCode;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAttached() {
        ClientConnection conn = current;
        return conn != null && !conn.closed;
    }

    /** APP 手动 / 二维码要填的地址。 */
    public String pairingUrl() {
        return "ws://" + lanIp + ":" + port() + "?tid=" + pairingCode;
    }

    /** 给当前 APP 发一条文本帧。没有连接时返回 false。 */
    public boolean sendText(String json) {
        ClientConnection conn = current;
        if (conn == null || conn.closed) {
            return false;
        }
        return conn.sendText(json);
    }

    // ------------------------------------------------------------------
    // HTTP / WebSocket 底层
    // ------------------------------------------------------------------

    private static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int matched = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buffer.write(b);
            // 检测 \r\n\r\n
            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
                if (matched == 4) {
                    return buffer.toString(StandardCharsets.ISO_8859_1);
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        return null;
    }

    private static String parseRequestPath(String header) {
        int end = header.indexOf('\r');
        String first = end < 0 ? header : header.substring(0, end);
        String[] parts = first.split(" ");
        return parts.length > 1 ? parts[1] : "/";
    }

    private static String parseHeader(String header, String name) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String queryParam(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).equalsIgnoreCase(name)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String acceptKey(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IOException("无法计算 Sec-WebSocket-Accept", e);
        }
    }

    private static void writeHttp(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static long readUnsigned(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("连接已结束");
            }
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("连接已结束");
            }
            offset += read;
        }
        return data;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 无所谓
        }
    }

    private static String randomHex(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < chars; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }

    /**
     * 猜本机局域网地址：UDP connect 不会真的发包，但能让系统选出出口网卡。
     */
    static String guessLanIp() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName("223.5.5.5"), 53);
            String ip = probe.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.") && !ip.startsWith("0.")) {
                return ip;
            }
        } catch (Throwable ignored) {
            // 没网时退回下面
        }
        try {
            // getNetworkInterfaces() 返回的是 Enumeration，不能直接 for-each
            for (var address : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!address.isUp() || address.isLoopback()) {
                    continue;
                }
                for (var iface : java.util.Collections.list(address.getInetAddresses())) {
                    if (iface instanceof java.net.Inet4Address && !iface.isLoopbackAddress()) {
                        return iface.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "127.0.0.1";
    }

    /** 一条已握手的连接。写操作要串行，所以 sendFrame 是 synchronized 的。 */
    private static final class ClientConnection {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        volatile boolean closed;

        ClientConnection(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        synchronized boolean sendText(String text) {
            if (closed) {
                return false;
            }
            return sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized boolean sendFrame(int opcode, byte[] payload) {
            if (closed) {
                return false;
            }
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
                frame.write(0x80 | opcode);
                int length = payload.length;
                if (length < 126) {
                    frame.write(length);
                } else if (length < 65536) {
                    frame.write(126);
                    frame.write((length >> 8) & 0xFF);
                    frame.write(length & 0xFF);
                } else {
                    frame.write(127);
                    for (int i = 7; i >= 0; i--) {
                        frame.write((int) (((long) length >> (8 * i)) & 0xFF));
                    }
                }
                frame.write(payload);
                out.write(frame.toByteArray());
                out.flush();
                return true;
            } catch (IOException e) {
                closed = true;
                return false;
            }
        }

        void close(int code, String reason) {
            if (closed) {
                return;
            }
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            sendFrame(0x8, payload);
            closed = true;
            closeQuietly(socket);
        }
    }
}
