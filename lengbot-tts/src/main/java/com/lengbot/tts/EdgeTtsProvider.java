package com.lengbot.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * EdgeTTS Provider —— 纯 Java 复刻 edge-tts（rany2/edge-tts，当前协议）的微软在线 TTS。
 *
 * <p>协议要点（与 rany2/edge-tts 的 src/edge_tts/{communicate,constants,drm}.py 对齐）：
 * <ol>
 *   <li>WebSocket 接入点 {@code wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1}，
 *       查询参数携带 {@code TrustedClientToken}、动态 {@code Sec-MS-GEC}（本地 SHA-256 计算）、
 *       {@code Sec-MS-GEC-Version=1-<Chromium版本>}、{@code ConnectionId}；</li>
 *   <li>建连后先发 {@code Path:speech.config}（合成配置 JSON），再发 {@code Path:ssml}（SSML）；
 *       音频以二进制帧返回（前 2 字节为头部长度，其后为 headers，再其后为裸音频字节）；</li>
 *   <li>以文本帧 {@code Path:turn.end} 结束合成；</li>
 *   <li>Sec-MS-GEC = SHA-256( windowsFileTimeTicks + TRUSTED_CLIENT_TOKEN ) 的大写十六进制，
 *       其中 windowsFileTimeTicks = ((unixSeconds + 11644473600) 向下取整到 300s) × 10^7；</li>
 *   <li>音色列表来自
 *       {@code https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=...}。</li>
 * </ol>
 * 所有端点 / 令牌 / 版本 / User-Agent 均可经 {@code lengbot.tts.edge-tts.*} 覆盖。
 *
 * <p><b>注意</b>：该实现复刻微软面向 Edge 浏览器开放的免费在线语音合成服务，属对公开服务的复用；
 * 若微软调整端点或令牌机制需同步更新。生产环境建议通过 {@code GET /api/tts/health} 联调验证。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsProvider {

    private final TtsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Windows 纪元（1601-01-01 UTC）相对 Unix 纪元的秒差，用于换算 Windows FILETIME */
    private static final long WIN_EPOCH_SECONDS = 11644473600L;
    /** 5 分钟窗口（秒） */
    private static final long WINDOW_SECONDS = 300L;
    /** 每秒对应的 100 纳秒间隔数（Windows FILETIME 单位） */
    private static final long HUNDRED_NS_PER_SECOND = 10_000_000L;

    /** Sec-MS-GEC 令牌缓存（含过期时间） */
    private volatile TokenCache tokenCache;

    /** 可用 neural 音色名缓存（含过期时间），用于合成前校验，避免把非 neural 音色发给微软导致 1007 */
    private volatile Set<String> validVoiceNames;
    private volatile long validVoiceExpireAt;
    private static final long VOICE_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** single-flight placeholder: only the winner fetches over HTTP outside the lock */
    private volatile CompletableFuture<Set<String>> validVoiceNamesFuture;

    @Override
    public TtsAudio synthesize(TtsRequest request) {
        String text = request.getText();
        if (text == null || text.isBlank()) {
            throw new TtsException("TTS 文本为空");
        }
        String voice = request.getVoice() != null && !request.getVoice().isBlank()
                ? request.getVoice() : properties.getDefaultVoice();
        String rate = resolveRate(request.getRate());
        String pitch = resolvePitch(request.getPitch());
        String format = request.getFormat() != null && !request.getFormat().isBlank()
                ? request.getFormat() : properties.getFormat();

        TtsProperties.EdgeTts cfg = properties.getEdgeTts();
        // 防御：若请求的音色并非 edge-tts 的 neural 音色（如 mock 音色、标准音色或拼写错误），
        // 微软会返回 1007「standard voices no longer supported for new users」。
        // 校验不通过则回退到默认 neural 音色，保证 synthesize 永不因非法音色名而 1007。
        Set<String> valid = getValidVoiceNames();
        boolean known = valid != null && valid.contains(voice);
        boolean looksNeural = voice != null && voice.toLowerCase().endsWith("neural");
        if (!known && !looksNeural) {
            log.warn("[EdgeTTS] 请求音色 '{}' 非有效 edge-tts neural 音色，回退默认 '{}'",
                    voice, properties.getDefaultVoice());
            voice = properties.getDefaultVoice();
        }
        String secMsGec = getSecMsGec(cfg);
        String wsUrl = buildWsUrl(cfg, secMsGec);
        String configMsg = buildConfigMessage(format);
        String ssmlMsg = buildSsmlMessage(text, voice, rate, pitch);

        CompletableFuture<byte[]> future = new CompletableFuture<>();

        String muid = muid();
        // 说明：java.net.http.WebSocket.Builder 禁止设置 Origin / Sec-WebSocket-Version 等握手头，
        // 因此这里只设置允许自定义的请求头（User-Agent / Cookie / 缓存控制 / 语言等）。
        // 微软该端点对 Origin 通常不强制校验；如需严格复刻可将 Origin 加入握手头（需自行实现 WS 握手）。
        WebSocket ws;
        try {
            ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("User-Agent", cfg.getUserAgent())
                    .header("Cookie", "muid=" + muid + ";")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Accept-Encoding", "gzip, deflate, br, zstd")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                    .buildAsync(URI.create(wsUrl), new EdgeWsListener(future, configMsg, ssmlMsg))
                    .get(cfg.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new TtsException("EdgeTTS 建连超时(" + cfg.getConnectTimeoutMs() + "ms)");
        } catch (Exception e) {
            throw new TtsException("EdgeTTS 建连失败: " + e.getMessage(), e);
        }

        try {
            byte[] audio = future.get(cfg.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
            if (audio == null || audio.length == 0) {
                throw new TtsException("EdgeTTS 返回空音频");
            }
            return TtsAudio.builder()
                    .data(audio)
                    .contentType(contentTypeFor(format))
                    .format(format)
                    .build();
        } catch (TimeoutException e) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "timeout");
            } catch (Exception ignore) {
                // ignore
            }
            throw new TtsException("EdgeTTS 合成超时(" + cfg.getReadTimeoutMs() + "ms)");
        } catch (TtsException e) {
            throw e;
        } catch (Exception e) {
            throw new TtsException("EdgeTTS 合成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TtsVoice> listVoices() {
        TtsProperties.EdgeTts cfg = properties.getEdgeTts();
        String url = buildVoicesUrl(cfg);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
                .header("User-Agent", cfg.getUserAgent())
                .header("Accept", "*/*")
                .header("Sec-CH-UA",
                        "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\""
                                + chromiumMajor(cfg) + "\", \"Chromium\";v=\"" + chromiumMajor(cfg) + "\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .GET();
        try {
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("[EdgeTTS] 音色列表获取失败: status={}", resp.statusCode());
                return List.of();
            }
            JsonNode arr = objectMapper.readTree(resp.body());
            List<TtsVoice> voices = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    voices.add(TtsVoice.builder()
                            .name(n.path("ShortName").asText(""))
                            .friendlyName(n.path("FriendlyName").asText(""))
                            .locale(n.path("Locale").asText(""))
                            .gender(n.path("Gender").asText(""))
                            .provider("edge-tts")
                            .build());
                }
            }
            return voices;
        } catch (Exception e) {
            log.warn("[EdgeTTS] 音色列表解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String name() {
        return "edge-tts";
    }

    @Override
    public TtsProviderHealth health() {
        TtsProviderHealth h = new TtsProviderHealth();
        TtsProperties.EdgeTts cfg = properties.getEdgeTts();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(buildVoicesUrl(cfg)))
                .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
                .header("User-Agent", cfg.getUserAgent())
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = resp.statusCode() == 200;
            h.setAvailable(ok);
            h.setDetail(ok ? "edge-tts 服务可连通（voices 接口 200）" : "voices 接口返回 " + resp.statusCode());
        } catch (Exception e) {
            h.setAvailable(false);
            h.setDetail("连通性自检失败: " + e.getMessage());
        }
        return h;
    }

    // ===================== URL / 令牌 =====================

    private String buildWsUrl(TtsProperties.EdgeTts cfg, String secMsGec) {
        return "wss://" + cfg.getHost() + cfg.getWebsocketPath()
                + "?TrustedClientToken=" + cfg.getTrustedClientToken()
                + "&ConnectionId=" + connectId()
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=1-" + cfg.getChromiumFullVersion();
    }

    private String buildVoicesUrl(TtsProperties.EdgeTts cfg) {
        return "https://" + cfg.getHost() + cfg.getVoicesPath()
                + "?trustedclienttoken=" + cfg.getTrustedClientToken();
    }

    /**
     * 取得当前 edge-tts 可用音色名集合（带缓存）。
     * 用于合成前校验请求音色是否合法；若清单暂时无法加载（网络异常）则返回 null，
     * 调用方退化为「是否以 Neural 结尾」的启发式判断，避免阻断合法合成。
     */
    private Set<String> getValidVoiceNames() {
        long now = System.currentTimeMillis();
        Set<String> cached = validVoiceNames;
        if (cached != null && validVoiceExpireAt > now) {
            return cached;
        }
        // single-flight: only the winner thread runs listVoices() (HTTP) outside the lock.
        // On cache expiry, other threads share the same future instead of blocking serially on the lock.
        CompletableFuture<Set<String>> future;
        synchronized (this) {
            // double-check after acquiring the lock
            if (validVoiceNames != null && validVoiceExpireAt > now) {
                return validVoiceNames;
            }
            if (validVoiceNamesFuture == null) {
                validVoiceNamesFuture = CompletableFuture.supplyAsync(() -> {
                    List<TtsVoice> live = listVoices();
                    if (live == null || live.isEmpty()) {
                        return null;
                    }
                    return live.stream()
                            .map(TtsVoice::getName)
                            .filter(n -> n != null && !n.isBlank())
                            .collect(Collectors.toSet());
                });
            }
            future = validVoiceNamesFuture;
        }
        try {
            Set<String> set = future.get(5, TimeUnit.SECONDS);
            if (set != null && !set.isEmpty()) {
                validVoiceNames = set;
                validVoiceExpireAt = System.currentTimeMillis() + VOICE_CACHE_TTL_MS;
                return set;
            }
            return null;
        } catch (Exception e) {
            log.warn("[EdgeTTS] 音色清单加载失败，跳过音色校验: {}", e.getMessage());
            return null;
        } finally {
            // clear the placeholder so the next expiry can re-fetch
            if (validVoiceNamesFuture == future) {
                validVoiceNamesFuture = null;
            }
        }
    }

    private String getSecMsGec(TtsProperties.EdgeTts cfg) {
        long now = System.currentTimeMillis();
        TokenCache cached = tokenCache;
        if (cached != null && cached.expireAt > now) {
            return cached.token;
        }
        synchronized (this) {
            if (tokenCache != null && tokenCache.expireAt > now) {
                return tokenCache.token;
            }
            String token = generateSecMsGec(cfg.getTrustedClientToken());
            tokenCache = new TokenCache(token, now + (long) cfg.getTokenTtlSeconds() * 1000L);
            return token;
        }
    }

    /**
     * 计算 Sec-MS-GEC：SHA-256( windowsFileTimeTicks + TRUSTED_CLIENT_TOKEN ) 的大写十六进制。
     * windowsFileTimeTicks = ((unixSeconds + 11644473600) 向下取整到 300s) × 10^7
     */
    static String generateSecMsGec(String trustedClientToken) {
        long unixSeconds = System.currentTimeMillis() / 1000L;
        long ticks = unixSeconds + WIN_EPOCH_SECONDS;
        ticks -= (ticks % WINDOW_SECONDS);
        BigInteger fileTime = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(HUNDRED_NS_PER_SECOND));
        String strToHash = fileTime.toString() + trustedClientToken;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(strToHash.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (Exception e) {
            throw new TtsException("Sec-MS-GEC 计算失败", e);
        }
    }

    // ===================== 请求帧 =====================

    private String buildConfigMessage(String format) {
        return "X-Timestamp:" + nowRfc1123() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"" + format + "\"}}}}";
    }

    private String buildSsmlMessage(String text, String voice, String rate, String pitch) {
        String ssml = buildSsml(text, voice, rate, pitch);
        return "X-RequestId:" + connectId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + nowRfc1123() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
    }

    private String buildSsml(String text, String voice, String rate, String pitch) {
        String lang = localeOf(voice);
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + lang + "'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='+0%'>"
                + xmlEscape(text)
                + "</prosody></voice></speak>";
    }

    // ===================== 工具 =====================

    private static String chromiumMajor(TtsProperties.EdgeTts cfg) {
        String v = cfg.getChromiumFullVersion();
        int dot = v.indexOf('.');
        return dot > 0 ? v.substring(0, dot) : v;
    }

    private static String nowRfc1123() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atZone(ZoneOffset.UTC));
    }

    private static String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String muid() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    private static String localeOf(String voice) {
        if (voice == null) return "zh-CN";
        String[] parts = voice.split("-", 3);
        if (parts.length >= 2) return parts[0] + "-" + parts[1];
        return "zh-CN";
    }

    private static String xmlEscape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String resolveRate(Object rate) {
        if (rate == null) return "+0%";
        if (rate instanceof Number n) return formatPercent(n.doubleValue());
        return rate.toString();
    }

    private static String resolvePitch(Object pitch) {
        if (pitch == null) return "+0Hz";
        if (pitch instanceof Number n) return formatHz(n.doubleValue());
        return pitch.toString();
    }

    private static String formatPercent(double r) {
        long p = Math.round((r - 1.0) * 100.0);
        return (p >= 0 ? "+" : "") + p + "%";
    }

    private static String formatHz(double p) {
        long h = Math.round((p - 1.0) * 100.0);
        return (h >= 0 ? "+" : "") + h + "Hz";
    }

    private static String contentTypeFor(String format) {
        if (format == null) return "audio/mpeg";
        String f = format.toLowerCase();
        if (f.contains("ogg") || f.contains("webm")) return "audio/ogg";
        if (f.contains("wav") || f.contains("pcm") || f.contains("raw")) return "audio/wav";
        if (f.contains("amr")) return "audio/amr";
        return "audio/mpeg";
    }

    // ===================== 内部类 =====================

    /** WebSocket 监听：发两条文本帧，收集二进制音频，遇 turn.end 完成 future。 */
    private static final class EdgeWsListener implements WebSocket.Listener {
        private final CompletableFuture<byte[]> future;
        private final String configMsg;
        private final String ssmlMsg;
        private final ByteArrayOutputStream audioOut = new ByteArrayOutputStream();

        EdgeWsListener(CompletableFuture<byte[]> future, String configMsg, String ssmlMsg) {
            this.future = future;
            this.configMsg = configMsg;
            this.ssmlMsg = ssmlMsg;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.sendText(configMsg, true);
            webSocket.sendText(ssmlMsg, true);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String path = extractHeader(data.toString(), "Path");
            if ("turn.end".equalsIgnoreCase(path)) {
                future.complete(audioOut.toByteArray());
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
                } catch (Exception ignore) {
                    // ignore
                }
                return CompletableFuture.completedFuture(null);
            }
            if (!future.isDone()) {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if (bytes.length >= 2) {
                int headerLength = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
                int audioStart = 2 + headerLength;
                if (headerLength <= bytes.length && audioStart <= bytes.length) {
                    byte[] audio = Arrays.copyOfRange(bytes, audioStart, bytes.length);
                    if (audio.length > 0) {
                        audioOut.write(audio, 0, audio.length);
                    }
                }
            }
            if (!future.isDone()) {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!future.isDone()) {
                future.completeExceptionally(error);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!future.isDone()) {
                byte[] partial = audioOut.toByteArray();
                if (partial.length > 0) {
                    future.complete(partial);
                } else {
                    future.completeExceptionally(
                            new TtsException("EdgeTTS 连接提前关闭: " + statusCode + " " + reason));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        private static String extractHeader(String message, String headerName) {
            int idx = message.indexOf("\r\n\r\n");
            String headerPart = idx >= 0 ? message.substring(0, idx) : message;
            for (String line : headerPart.split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim();
                    if (headerName.equalsIgnoreCase(k)) {
                        return line.substring(c + 1).trim();
                    }
                }
            }
            return null;
        }
    }

    private static final class TokenCache {
        final String token;
        final long expireAt;

        TokenCache(String token, long expireAt) {
            this.token = token;
            this.expireAt = expireAt;
        }
    }
}
