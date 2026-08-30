package com.lengbot.tts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Mock TTS Provider —— 不调用任何外部服务，生成一段短促提示音（WAV）用于：
 * <ul>
 *   <li>开发/联调阶段验证「后端 TTS → 前端播放 → 数字人口型」整条链路</li>
 *   <li>EdgeTTS 等真实 Provider 不可用时的兜底</li>
 * </ul>
 * 配置 {@code lengbot.tts.provider: mock} 启用。
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class MockTtsProvider implements TtsProvider {

    private final TtsProperties properties;

    @Override
    public TtsAudio synthesize(TtsRequest request) {
        String text = request.getText() == null ? "" : request.getText();
        // 文本越长，提示音稍长（封顶 2s），便于直观感知「读到了内容」
        int ms = Math.min(2000, properties.getMock().getDurationMs() + text.length() * 8);
        return TtsAudio.builder()
                .data(generateWav(properties.getMock().getSampleRate(),
                        properties.getMock().getFrequencyHz(), ms))
                .contentType("audio/wav")
                .format("wav")
                .build();
    }

    @Override
    public List<TtsVoice> listVoices() {
        return List.of(
                TtsVoice.builder().name("mock-zh-CN").friendlyName("Mock 中文(测试)").locale("zh-CN").gender("Female").provider("mock").build(),
                TtsVoice.builder().name("mock-en-US").friendlyName("Mock English(Test)").locale("en-US").gender("Male").provider("mock").build()
        );
    }

    @Override
    public String name() {
        return "mock";
    }

    /** 生成一段 16-bit PCM 单声道 WAV 正弦波。 */
    private byte[] generateWav(int sampleRate, int freqHz, int durationMs) {
        int numSamples = (int) ((long) sampleRate * durationMs / 1000L);
        int dataSize = numSamples * 2; // 16-bit
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes());
        // fmt chunk
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);            // PCM
        buf.putShort((short) 1);            // mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);         // byte rate
        buf.putShort((short) 2);            // block align
        buf.putShort((short) 16);           // bits per sample
        // data chunk
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        double amp = 0.15; // 低音量，避免长时间刺耳
        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / sampleRate;
            double env = Math.min(1.0, t * 20.0) * Math.min(1.0, (durationMs / 1000.0 - t) * 20.0);
            short s = (short) (Math.sin(2 * Math.PI * freqHz * t) * amp * env * 32767);
            buf.putShort(s);
        }
        return buf.array();
    }
}
