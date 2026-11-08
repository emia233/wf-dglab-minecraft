package com.civon.wildestfantasy.client.dglab;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DG-LAB SOCKET V4 协议的控制端（controller）实现。
 *
 * <p>连接模型：
 * <pre>
 *   本模组（控制方） --wss--&gt; 官方 V4 中转服务 &lt;--wss-- DG-LAB APP（被控方） --BLE--&gt; 郊狼 3.0
 * </pre>
 * 控制方连上后服务端下发 {@code hello}，其中的 clientId 就是配对用的控制方 ID；
 * 把该 ID 拼进配对 URL 生成二维码，APP 扫码接入后服务端下发 {@code client_attached}，
 * 此后即可通过 {@code device.op} RPC 发送强度任务。
 *
 * <p>本类只发“强度”，永远不发波形（{@link DglabAction#APPEND_PULSE} 不在此实现）。
 * 波形由用户在 APP 内自行选择并点击开始，符合 3.0 主机的使用方式。
 */
public final class DglabV4Client {
    private static final Logger LOG = LoggerFactory.getLogger("wildestfantasy/dglab");

    /** 官方 V4 中转服务地址（dglab-kit 的默认值）。 */
    public static final String DEFAULT_SERVER = "wss://trex.dungeon-lab.cn/v4";
    private static final String APP_SCHEME = "https://dungeon-lab.cn/s/?v=1&action=socket&url=";

    public enum State {
        /** 未连接 */
        DISCONNECTED,
        /** 正在建立 WebSocket */
        CONNECTING,
        /** 已连上中转服务，等待 APP 扫码接入 */
        WAITING_APP,
        /** APP 已接入，可以下发强度 */
        READY
    }

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, DglabDevice> devices = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong requestSeq = new AtomicLong();
    private final StringBuilder partialText = new StringBuilder();

    private volatile State state = State.DISCONNECTED;
    private volatile WebSocket socket;
    private volatile String controllerId;
    private volatile String appClientId;
    private volatile String serverUrl = DEFAULT_SERVER;
    private volatile String lastError;
    private volatile int lastSentA = -1;
    private volatile int lastSentB = -1;

    // ------------------------------------------------------------------
    // 连接生命周期
    // ------------------------------------------------------------------

    public synchronized void connect(String url) {
        if (state != State.DISCONNECTED) {
            LOG.info("连接请求被忽略，当前状态 {}", state);
            return;
        }
        this.serverUrl = (url == null || url.isBlank()) ? DEFAULT_SERVER : url.trim();
        this.lastError = null;
        setState(State.CONNECTING);

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                LOG.info("WebSocket 已打开: {}", serverUrl);
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partialText.append(data);
                if (last) {
                    String raw = partialText.toString();
                    partialText.setLength(0);
                    try {
                        handleFrame(raw);
                    } catch (Throwable t) {
                        LOG.warn("处理下行帧失败: {}", raw, t);
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                LOG.info("WebSocket 已关闭: {} {}", statusCode, reason);
                handleDisconnected("连接已关闭 (" + statusCode + ")");
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                LOG.warn("WebSocket 错误", error);
                handleDisconnected("网络错误: " + error.getMessage());
            }
        };

        try {
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(serverUrl), listener)
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            LOG.warn("连接 {} 失败", serverUrl, err);
                            handleDisconnected("连接失败: " + err.getMessage());
                        } else {
                            this.socket = ws;
                        }
                    });
        } catch (Throwable t) {
            handleDisconnected("无法建立连接: " + t.getMessage());
        }
    }

    public synchronized void disconnect() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null);
            } catch (Throwable ignored) {
                // 已经断开时忽略
            }
        }
        handleDisconnected("手动断开");
    }

    private void handleDisconnected(String reason) {
        if (reason != null) {
            lastError = reason;
        }
        socket = null;
        appClientId = null;
        devices.clear();
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(reason)));
        pending.clear();
        controllerId = null;
        lastSentA = -1;
        lastSentB = -1;
        if (state != State.DISCONNECTED) {
            setState(State.DISCONNECTED);
        }
    }

    private void setState(State next) {
        State old = state;
        state = next;
        if (old != next) {
            LOG.info("DGLab 状态 {} -> {}", old, next);
            for (Runnable r : stateListeners) {
                try {
                    r.run();
                } catch (Throwable t) {
                    LOG.debug("状态监听器异常", t);
                }
            }
        }
    }

    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    // ------------------------------------------------------------------
    // 下行帧处理
    // ------------------------------------------------------------------

    private void handleFrame(String raw) {
        JsonObject frame;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }
            frame = parsed.getAsJsonObject();
        } catch (Throwable t) {
            LOG.debug("忽略非 JSON 帧: {}", raw);
            return;
        }

        String type = stringOr(frame, "type", "");
        switch (type) {
            case "hello" -> {
                controllerId = stringOr(frame, "clientId", null);
                LOG.info("控制方 ID = {}", controllerId);
                setState(State.WAITING_APP);
            }
            case "client_attached" -> {
                appClientId = stringOr(frame, "clientId", null);
                LOG.info("APP 已接入: {}", appClientId);
                setState(State.READY);
                requestDevices();
            }
            case "client_disconnected" -> {
                LOG.info("APP 已断开: {}", stringOr(frame, "clientId", null));
                appClientId = null;
                devices.clear();
                lastSentA = -1;
                lastSentB = -1;
                setState(State.WAITING_APP);
            }
            case "heartbeat" -> {
                // 服务端心跳，控制方无需回复
            }
            case "ping" -> sendRaw("{\"type\":\"pong\"}");
            case "pong" -> {
                // 服务端级 pong
            }
            case "idle_timeout" -> {
                LOG.warn("服务端空闲超时：5 分钟内没有 APP 接入，连接即将关闭");
                lastError = "空闲超时，请重新生成二维码";
                handleDisconnected(lastError);
            }
            case "error" -> {
                lastError = "服务端错误 " + stringOr(frame, "code", "?");
                LOG.warn("{} {}", lastError, stringOr(frame, "message", ""));
            }
            case "message" -> handleMessage(frame.getAsJsonObject("data"));
            default -> LOG.debug("未处理的帧类型: {}", type);
        }
    }

    private void handleMessage(JsonObject data) {
        if (data == null) {
            return;
        }
        String t = stringOr(data, "t", "");

        if ("resp".equals(t)) {
            String reqId = stringOr(data, "reqId", null);
            if (reqId == null) {
                reqId = stringOr(data, "requestId", null);
            }
            CompletableFuture<JsonObject> fut = reqId == null ? null : pending.remove(reqId);
            if (fut != null) {
                fut.complete(data);
            }
            return;
        }

        if ("ev".equals(t)) {
            handleEvent(data);
            return;
        }

        if ("req".equals(t)) {
            // 被控方发来的请求（例如远端 ping），回一个空响应避免对端挂起
            String reqId = stringOr(data, "reqId", stringOr(data, "requestId", null));
            if (reqId != null) {
                JsonObject resp = new JsonObject();
                resp.addProperty("t", "resp");
                resp.addProperty("reqId", reqId);
                resp.add("result", new JsonObject());
                sendMessage(resp);
            }
        }
    }

    private void handleEvent(JsonObject event) {
        String ev = stringOr(event, "ev", "");
        switch (ev) {
            case "devices.snapshot" -> {
                devices.clear();
                JsonArray arr = event.has("devices") && event.get("devices").isJsonArray()
                        ? event.getAsJsonArray("devices")
                        : new JsonArray();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject d = el.getAsJsonObject();
                    String slotId = stringOr(d, "slotId", null);
                    if (slotId == null) {
                        continue;
                    }
                    devices.put(slotId, new DglabDevice(
                            slotId,
                            stringOr(d, "name", slotId),
                            stringOr(d, "type", "unknown")));
                }
                LOG.info("设备快照: {} 个设备", devices.size());
            }
            case "devices.patch" -> {
                if (event.has("removed") && event.get("removed").isJsonArray()) {
                    for (JsonElement el : event.getAsJsonArray("removed")) {
                        if (el.isJsonPrimitive()) {
                            devices.remove(el.getAsString());
                        }
                    }
                }
                if (event.has("added") && event.get("added").isJsonArray()) {
                    for (JsonElement el : event.getAsJsonArray("added")) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject d = el.getAsJsonObject();
                        String slotId = stringOr(d, "slotId", null);
                        if (slotId != null) {
                            devices.put(slotId, new DglabDevice(
                                    slotId,
                                    stringOr(d, "name", slotId),
                                    stringOr(d, "type", "unknown")));
                        }
                    }
                }
            }
            case "slots.patch" -> {
                // 插槽状态/属性增量，目前不影响强度下发
            }
            case "custom.action" -> LOG.info("APP 自定义动作: {}", stringOr(event, "action", "?"));
            default -> LOG.debug("未处理的事件: {}", ev);
        }
    }

    // ------------------------------------------------------------------
    // RPC 发送
    // ------------------------------------------------------------------

    public CompletableFuture<JsonObject> requestDevices() {
        JsonObject req = newRequest("devices.get", new JsonObject());
        return sendRequest(req, "devices.get");
    }

    /** 设置临时强度：到期后由主机自动回落，适合作为“事件脉冲”。 */
    public void setTempIntensity(String slotId, int channel, int value, int durationMs) {
        JsonObject op = buildOperate(slotId, channel, DglabAction.SET_TEMP_INTENSITY, value);
        if (durationMs > 0) {
            op.addProperty("d", durationMs);
        }
        sendOperate(op);
    }

    /** 归零通道强度。V4 的 SetIntensity 只接受目标值 0，其它值请用 {@link #setTempIntensity}。 */
    public void resetIntensity(String slotId, int channel) {
        sendOperate(buildOperate(slotId, channel, DglabAction.SET_INTENSITY, 0));
    }

    /** 相对增减强度。 */
    public void addIntensity(String slotId, int channel, int delta) {
        sendOperate(buildOperate(slotId, channel, DglabAction.ADD_INTENSITY, delta));
    }

    /** 清理所有任务/波形队列。 */
    public void clearAll() {
        JsonObject data = new JsonObject();
        JsonObject req = newRequest("device.op.clear", data);
        sendRequest(req, "device.op.clear");
        lastSentA = -1;
        lastSentB = -1;
    }

    /** 清理指定通道。 */
    public void clearChannel(String slotId, int channel) {
        JsonObject data = new JsonObject();
        data.addProperty("s", slotId);
        data.addProperty("c", channel);
        sendRequest(newRequest("device.op.clear", data), "device.op.clear");
        if (channel == DglabAction.CHANNEL_A) {
            lastSentA = -1;
        } else {
            lastSentB = -1;
        }
    }

    private JsonObject buildOperate(String slotId, int channel, int action, int value) {
        int clamped = Math.max(0, Math.min(DglabAction.MAX_INTENSITY, value));
        JsonObject op = new JsonObject();
        op.addProperty("s", slotId);
        op.addProperty("c", channel);
        op.addProperty("t", action);
        op.addProperty("v", clamped);
        // 同类任务互相替换，避免队列堆积
        op.addProperty("im", true);
        return op;
    }

    private void sendOperate(JsonObject op) {
        if (state != State.READY) {
            return;
        }
        // device.op 的 RPC 响应要等任务结束/清理/替换后才会回来（可能几秒甚至更久），
        // 所以这里不登记 pending，只保证帧送到了。否则每次心跳都会积压一个超时 future。
        sendMessage(newRequest("device.op", op));
    }

    private JsonObject newRequest(String method, JsonObject data) {
        JsonObject req = new JsonObject();
        req.addProperty("t", "req");
        req.addProperty("reqId", "wf-" + requestSeq.incrementAndGet());
        req.addProperty("m", method);
        req.add("data", data);
        return req;
    }

    private CompletableFuture<JsonObject> sendRequest(JsonObject request, String label) {
        String reqId = request.get("reqId").getAsString();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(reqId, future);
        future.orTimeout(5, TimeUnit.SECONDS).whenComplete((r, e) -> {
            pending.remove(reqId);
            if (e != null) {
                LOG.debug("{} 请求 {} 未收到响应: {}", label, reqId, e.toString());
            }
        });
        if (!sendMessage(request)) {
            pending.remove(reqId);
            future.completeExceptionally(new IllegalStateException("连接不可用"));
        }
        return future;
    }

    private boolean sendMessage(JsonObject payload) {
        String target = appClientId;
        if (target == null) {
            return false;
        }
        // V4 的 RPC 负载要包在 message 帧里，clientId 指出目标被控方
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "message");
        frame.addProperty("clientId", target);
        frame.add("data", payload);
        return sendRawFrame(frame);
    }

    private boolean sendRawFrame(JsonObject frame) {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) {
            return false;
        }
        try {
            ws.sendText(gson.toJson(frame), true);
            return true;
        } catch (Throwable t) {
            LOG.debug("发送失败", t);
            return false;
        }
    }

    private void sendRaw(String json) {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) {
            return;
        }
        try {
            ws.sendText(json, true);
        } catch (Throwable ignored) {
            // 连接已断开
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public State state() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public String controllerId() {
        return controllerId;
    }

    public String appClientId() {
        return appClientId;
    }

    public String lastError() {
        return lastError;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public List<DglabDevice> devices() {
        return new ArrayList<>(devices.values());
    }

    /** 第一个可用设备（绝大多数玩家只会连一台主机）。 */
    public DglabDevice primaryDevice() {
        return devices.values().stream().findFirst().orElse(null);
    }

    /**
     * APP 侧要连接的配对地址。
     *
     * <p>实测：{@code wss://trex.dungeon-lab.cn/v4?tid=xxx} 可以配对，
     * 而官方 README 示例里多写一个斜杠的 {@code /v4/?tid=xxx} 会被服务端直接断开（close 1006）。
     * 所以这里不拼斜杠。
     */
    public String pairingUrl() {
        String id = controllerId;
        if (id == null) {
            return null;
        }
        String base = serverUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "?tid=" + id;
    }

    /** 渲染成二维码的完整地址。 */
    public String qrCodeUrl() {
        String pair = pairingUrl();
        if (pair == null) {
            return null;
        }
        return APP_SCHEME + java.net.URLEncoder.encode(pair, StandardCharsets.UTF_8);
    }

    /** 记录最近一次真正下发成功的强度，供 HUD 显示。 */
    void markSent(int channel, int value) {
        if (channel == DglabAction.CHANNEL_A) {
            lastSentA = value;
        } else {
            lastSentB = value;
        }
    }

    public int lastSent(int channel) {
        return channel == DglabAction.CHANNEL_A ? lastSentA : lastSentB;
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
