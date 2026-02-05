package com.tencent.twetalk_audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.tencent.twetalk_audio.config.AudioConfig;
import com.tencent.twetalk_audio.config.AudioFormatType;
import com.tencent.twetalk_audio.listener.OnPlayStateListener;
import com.tencent.twetalk_audio.listener.OnRecordDataListener;
import com.tencent.twetalk_audio.opus.OpusBridge;
import com.tencent.twetalk_audio.opus.OpusEncoderParams;
import com.tencent.twetalk_audio.utils.PcmUtil;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talk 统一音频控制类
 * 整合音频采集和播放功能，提供统一的生命周期管理
 */
public class TalkAudioController {
    private static final String TAG = "TalkAudioController";

    // 错误码
    public static final int ERROR_NOT_INITIALIZED = 1;
    public static final int ERROR_ALREADY_INITIALIZED = 2;
    public static final int ERROR_AUDIO_RECORD_INIT = 3;
    public static final int ERROR_OPUS_ENCODER_INIT = 4;
    public static final int ERROR_RECORDING = 5;
    public static final int ERROR_TRACK_INIT = 6;
    public static final int ERROR_DECODER_INIT = 7;
    public static final int ERROR_PLAY = 8;

    private final Context context;
    private AudioConfig audioConfig;

    // ==================== 采集相关 ====================
    private AudioRecord audioRecord;
    private Thread recordThread;
    private AudioManager audioManager;
    private Integer previousAudioMode;
    private final int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;

    // Opus 编码器
    private long opusEncoderHandle = 0;
    private OpusBridge opusBridge = null;

    // 采集状态
    private volatile boolean isRecordInitialized = false;
    private volatile boolean isRecording = false;

    // 采集音频参数
    private int recordFrameBytes = 0;
    private int recordBufferSize = 0;

    // ==================== 播放相关 ====================
    private final ExecutorService playExecutor;
    private AudioTrack audioTrack;
    private int currentPlaySampleRate = 0;
    private int currentPlayChannels = 0;

    private final AtomicBoolean playStarted = new AtomicBoolean(false);
    private final AtomicBoolean playDraining = new AtomicBoolean(false);

    // Opus 解码器
    private long opusDecoderHandle = 0;

    // PCM 播放队列
    private final ConcurrentLinkedDeque<byte[]> pcmQueue = new ConcurrentLinkedDeque<>();
    private final int maxQueueBytes = 16000 * 2;  // 1 秒容量（16k单声道16bit）

    // ==================== 监听器 ====================
    private OnRecordDataListener recordDataListener;
    private OnPlayStateListener playStateListener;

    // ==================== 状态 ====================
    private volatile boolean isInitialized = false;
    private volatile boolean isMicMuted = false;      // 麦克风静音（不发送采集数据）
    private volatile boolean isSpeakerMuted = false;  // 扬声器静音（不播放声音）

    /**
     * 使用默认配置创建控制器
     */
    public TalkAudioController(Context context) {
        this(context, new AudioConfig());
    }

    /**
     * 使用自定义配置创建控制器
     */
    public TalkAudioController(Context context, AudioConfig config) {
        this.context = context.getApplicationContext();
        this.audioConfig = config != null ? config : new AudioConfig();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // 初始化播放线程池
        this.playExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                } catch (Throwable ignored) {}
                r.run();
            }, "TalkAudioPlayer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 设置音频采集数据监听器
     */
    public void setOnRecordDataListener(OnRecordDataListener listener) {
        this.recordDataListener = listener;
    }

    /**
     * 设置播放状态监听器
     */
    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.playStateListener = listener;
    }

    /**
     * 初始化音频控制器（初始化录音器）
     */
    public void init() {
        if (isInitialized) {
            Log.w(TAG, "TalkAudioController 已经初始化");
            return;
        }

        try {
            // 通话场景：提前切换到 MODE_IN_COMMUNICATION
            ensureCommunicationAudioMode();

            // 初始化 AudioRecord
            initAudioRecord();

            // 初始化 Opus 编码器（如果需要）
            if (audioConfig.formatType == AudioFormatType.OPUS) {
                initOpusEncoder();
            }

            isRecordInitialized = true;
            isInitialized = true;
            Log.i(TAG, "TalkAudioController 初始化成功");

        } catch (Exception e) {
            releaseRecordInternal();
            notifyRecordError(ERROR_AUDIO_RECORD_INIT, "初始化失败: " + e.getMessage());
        }
    }

    /**
     * 更新音频配置
     * 注意：更新配置后需要重新初始化才能生效
     */
    public void updateConfig(AudioConfig config) {
        if (config != null) {
            this.audioConfig = config;
            Log.i(TAG, "音频配置已更新，需要重新初始化才能生效");
        }
    }

    /**
     * 获取当前音频配置
     */
    public AudioConfig getAudioConfig() {
        return audioConfig;
    }

    // ==================== 采集相关实现 ====================

    /**
     * 开始音频采集
     */
    public void startRecord() {
        if (!isRecordInitialized) {
            notifyRecordError(ERROR_NOT_INITIALIZED, "录音器未初始化，请先调用 init()");
            return;
        }

        if (isRecording) {
            Log.d(TAG, "已经在录音中，忽略重复启动");
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        // 启动录音线程
        recordThread = new Thread(this::recordLoop, "TalkAudioRecorder");
        recordThread.start();

        Log.i(TAG, "开始录音");
    }

    /**
     * 停止音频采集
     */
    public void stopRecord() {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        // 等待录音线程结束
        if (recordThread != null) {
            try {
                recordThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待录音线程结束被中断", e);
            }
            recordThread = null;
        }

        try {
            if (audioRecord != null) {
                audioRecord.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "停止录音失败", e);
        }

        Log.i(TAG, "停止录音");
    }

    /**
     * 检查是否正在录音
     */
    public boolean isRecording() {
        return isRecording;
    }

    @SuppressLint("MissingPermission")
    private void initAudioRecord() {
        int channelConfig = audioConfig.channelCount == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;

        int audioFormat = audioConfig.bitDepth == 16
                ? AudioFormat.ENCODING_PCM_16BIT
                : AudioFormat.ENCODING_PCM_8BIT;

        // 计算帧大小
        int bytesPerSample = audioConfig.bitDepth / 8;
        int frameDurationMs = audioConfig.frameDuration.getDuration();
        recordFrameBytes = (audioConfig.sampleRate * bytesPerSample * audioConfig.channelCount * frameDurationMs) / 1000;

        // 计算缓冲区大小
        int minBuf = AudioRecord.getMinBufferSize(audioConfig.sampleRate, channelConfig, audioFormat);
        recordBufferSize = Math.max(minBuf * 2, recordFrameBytes * 2);

        // 创建 AudioRecord
        audioRecord = new AudioRecord(
                audioSource,
                audioConfig.sampleRate,
                channelConfig,
                audioFormat,
                recordBufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord 初始化失败");
        }

        Log.i(TAG, "AudioRecord 初始化成功: source=" + audioSource +
                ", sampleRate=" + audioConfig.sampleRate +
                ", channels=" + audioConfig.channelCount +
                ", bitDepth=" + audioConfig.bitDepth +
                ", frameBytes=" + recordFrameBytes +
                ", bufferSize=" + recordBufferSize);

        logActualAudioRecordParams();
    }

    private void logActualAudioRecordParams() {
        if (audioRecord == null) return;

        String actualFormat;
        switch (audioRecord.getAudioFormat()) {
            case AudioFormat.ENCODING_PCM_16BIT:
                actualFormat = "PCM_16BIT";
                break;
            case AudioFormat.ENCODING_PCM_8BIT:
                actualFormat = "PCM_8BIT";
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                actualFormat = "PCM_FLOAT";
                break;
            default:
                actualFormat = "UNKNOWN(" + audioRecord.getAudioFormat() + ")";
        }

        String bufferFrames = "N/A";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bufferFrames = String.valueOf(audioRecord.getBufferSizeInFrames());
        }

        Log.i(TAG, "AudioRecord 实际参数: source=" + audioSource +
                ", sampleRate=" + audioRecord.getSampleRate() +
                ", channels=" + audioRecord.getChannelCount() +
                ", format=" + actualFormat +
                ", bufferFrames=" + bufferFrames +
                ", sessionId=" + audioRecord.getAudioSessionId());
    }

    private void ensureCommunicationAudioMode() {
        if (audioManager == null) return;

        if (previousAudioMode == null) {
            previousAudioMode = audioManager.getMode();
        }

        if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            Log.i(TAG, "AudioManager 已切换到 MODE_IN_COMMUNICATION，previous=" + previousAudioMode);
        }
    }

    private void restoreAudioModeIfNeeded() {
        if (audioManager == null || previousAudioMode == null) return;

        if (audioManager.getMode() != previousAudioMode) {
            audioManager.setMode(previousAudioMode);
            Log.i(TAG, "AudioManager 已恢复为模式 " + previousAudioMode);
        }
        previousAudioMode = null;
    }

    /**
     * 懒加载获取 OpusBridge 实例
     * 只在使用 Opus 格式时才初始化，避免在不使用时加载 native 库
     */
    private OpusBridge getOpusBridge() {
        if (opusBridge == null) {
            try {
                opusBridge = OpusBridge.getInstance();
                Log.i(TAG, "OpusBridge 初始化成功");
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "OpusBridge 初始化失败，可能是缺少对应架构的 native 库", e);
                throw new RuntimeException("OpusBridge 初始化失败: " + e.getMessage(), e);
            }
        }
        return opusBridge;
    }

    private void initOpusEncoder() {
        try {
            OpusEncoderParams params = OpusEncoderParams.builder()
                    .sampleRate(audioConfig.sampleRate)
                    .channels(audioConfig.channelCount)
                    .build();

            opusEncoderHandle = getOpusBridge().createEncoder(params);

            if (opusEncoderHandle == 0L) {
                throw new RuntimeException("OpusEncoder 创建失败");
            }

            Log.i(TAG, "OpusEncoder 初始化成功: handle=" + opusEncoderHandle +
                    ", sampleRate=" + params.getSampleRate() +
                    ", channels=" + params.getChannels() +
                    ", bitrate=" + params.getBitrate() +
                    ", targetBytes=" + params.getTargetBytes());

        } catch (Exception e) {
            throw new RuntimeException("OpusEncoder 初始化失败", e);
        }
    }

    private void recordLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        } catch (Throwable ignored) {}

        byte[] readBuffer = new byte[recordBufferSize];
        byte[] frameBuffer = new byte[recordFrameBytes];
        int frameOffset = 0;

        Log.i(TAG, "录音线程开始");
        int totalReads = 0;
        int zeroReads = 0;
        int errorReads = 0;

        while (isRecording) {
            try {
                int readBytes = audioRecord.read(readBuffer, 0, readBuffer.length);

                if (readBytes <= 0) {
                    if (readBytes == 0) {
                        zeroReads++;
                    } else {
                        errorReads++;
                    }
                    Log.w(TAG, "读取音频数据失败: " + readBytes);
                    continue;
                }
                totalReads++;

                int cursor = 0;
                while (cursor < readBytes) {
                    int copyLen = Math.min(recordFrameBytes - frameOffset, readBytes - cursor);
                    System.arraycopy(readBuffer, cursor, frameBuffer, frameOffset, copyLen);
                    frameOffset += copyLen;
                    cursor += copyLen;

                    if (frameOffset == recordFrameBytes) {
                        processRecordData(frameBuffer.clone());
                        frameOffset = 0;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "录音循环异常", e);
                notifyRecordError(ERROR_RECORDING, "录音异常: " + e.getMessage());
                break;
            }
        }

        Log.i(TAG, "录音线程结束，统计: totalReads=" + totalReads +
                ", zeroReads=" + zeroReads + ", errorReads=" + errorReads);
    }

    private void processRecordData(byte[] pcmData) {
        // 麦克风静音时不回调数据
        if (isMicMuted) {
            return;
        }

        try {
            // 回调 PCM 数据
            if (recordDataListener != null) {
                recordDataListener.onPcmData(pcmData, pcmData.length);
            }

            // 如果配置为 Opus 格式，进行编码并回调
            if (audioConfig.formatType == AudioFormatType.OPUS) {
                byte[] opusData = encodeToOpus(pcmData);

                if (opusData != null && recordDataListener != null) {
                    recordDataListener.onOpusData(opusData, opusData.length);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "处理音频数据失败", e);
        }
    }

    private byte[] encodeToOpus(byte[] pcmData) {
        if (opusEncoderHandle == 0L) {
            Log.e(TAG, "OpusEncoder 未初始化");
            return null;
        }

        try {
            short[] shorts = PcmUtil.byteToShort(pcmData);
            return getOpusBridge().encode(opusEncoderHandle, shorts);
        } catch (Exception e) {
            Log.e(TAG, "Opus 编码失败", e);
            return null;
        }
    }

    private void releaseRecordInternal() {
        // 释放 Opus 编码器
        if (opusEncoderHandle != 0L && opusBridge != null) {
            opusBridge.releaseEncoder(opusEncoderHandle);
            opusEncoderHandle = 0;
        }

        // 释放 AudioRecord
        try {
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放 AudioRecord 失败", e);
        }

        restoreAudioModeIfNeeded();
        isRecordInitialized = false;
    }

    private void notifyRecordError(int errorCode, String message) {
        Log.e(TAG, "RecordError[" + errorCode + "]: " + message);
        if (recordDataListener != null) {
            recordDataListener.onRecordError(errorCode, message);
        }
    }

    // ==================== 播放相关实现 ====================

    /**
     * 播放音频数据
     * @param data 音频数据
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param format 音频格式
     */
    public void play(byte[] data, int sampleRate, int channels, AudioFormatType format) {
        // 扬声器静音时丢弃数据
        if (isSpeakerMuted) {
            return;
        }

        playExecutor.execute(() -> {
            boolean isPCM = format == AudioFormatType.PCM;
            ensureAudioTrack(sampleRate, channels, isPCM);

            byte[] pcmBytes;
            if (!isPCM) {
                // Opus 解码
                pcmBytes = decodeOpus(data, channels);
                if (pcmBytes == null) {
                    return;
                }
            } else {
                pcmBytes = data;
            }

            // 入队，超出容量则丢弃最老的帧
            while (queueBytes() + pcmBytes.length > maxQueueBytes) {
                pcmQueue.poll();
            }
            pcmQueue.offer(pcmBytes);

            // 唤起播放循环
            startDrainingLoop();
        });
    }

    /**
     * 播放 PCM 音频数据（使用当前配置的采样率和声道数）
     */
    public void playPcm(byte[] data) {
        play(data, audioConfig.sampleRate, audioConfig.channelCount, AudioFormatType.PCM);
    }

    /**
     * 播放 Opus 音频数据（使用当前配置的采样率和声道数）
     */
    public void playOpus(byte[] data) {
        play(data, audioConfig.sampleRate, audioConfig.channelCount, AudioFormatType.OPUS);
    }

    /**
     * 停止播放
     */
    public void stopPlay() {
        playExecutor.execute(() -> {
            playDraining.set(false);
            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                    audioTrack.flush();
                    audioTrack.stop();
                } catch (Exception e) {
                    Log.e(TAG, "停止播放失败", e);
                }
            }
            pcmQueue.clear();
        });
    }

    /**
     * 检查是否正在播放
     */
    public boolean isPlaying() {
        return playStarted.get() && audioTrack != null &&
                audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    private void ensureAudioTrack(int sampleRate, int channels, boolean isPCM) {
        // 检查是否需要重建 decoder
        if (!isPCM && opusDecoderHandle == 0L) {
            initOpusDecoder(sampleRate, channels);
        } else if (!isPCM && (currentPlaySampleRate != sampleRate || currentPlayChannels != channels)) {
            releaseOpusDecoder();
            initOpusDecoder(sampleRate, channels);
        }

        // 检查 AudioTrack 是否需要重建
        if (audioTrack != null &&
                audioTrack.getState() == AudioTrack.STATE_INITIALIZED &&
                sampleRate == currentPlaySampleRate &&
                channels == currentPlayChannels) {
            try {
                if (playStarted.compareAndSet(false, true)) {
                    audioTrack.play();
                } else if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.play();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "ensureAudioTrack: play error " + e.getMessage());
            }
            return;
        }

        // 重建 track
        releaseAudioTrackInternal();

        currentPlaySampleRate = sampleRate;
        currentPlayChannels = channels;

        int channelOut;
        switch (channels) {
            case 2:
                channelOut = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 1:
            default:
                channelOut = AudioFormat.CHANNEL_OUT_MONO;
                break;
        }

        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, AudioFormat.ENCODING_PCM_16BIT);
        int targetBuf = sampleRate * channels * 2 / 5;  // 约 200ms buffer
        int bufferSize = Math.max(minBuf * 2, targetBuf);

        // 通话场景使用 VOICE_COMMUNICATION 属性
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelOut)
                .build();

        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioTrack.play();
            playStarted.set(true);

            Log.i(TAG, "AudioTrack 初始化: sampleRate=" + sampleRate +
                    ", channels=" + channels + ", bufferSize=" + bufferSize + ", minBuf=" + minBuf);

            // 初次启动尝试预充多帧
            drainQueueNonBlocking(true, 5);

        } catch (Exception e) {
            Log.e(TAG, "AudioTrack 创建失败", e);
            notifyPlayError(ERROR_TRACK_INIT, "AudioTrack 创建失败: " + e.getMessage());
        }
    }

    private void initOpusDecoder(int sampleRate, int channels) {
        try {
            opusDecoderHandle = getOpusBridge().createDecoder(sampleRate, channels);

            if (opusDecoderHandle == 0L) {
                Log.e(TAG, "OpusDecoder 创建失败");
                notifyPlayError(ERROR_DECODER_INIT, "OpusDecoder 创建失败");
                return;
            }

            Log.i(TAG, "OpusDecoder 初始化成功: sampleRate=" + sampleRate + ", channels=" + channels);
        } catch (Exception e) {
            Log.e(TAG, "OpusDecoder 初始化失败", e);
            notifyPlayError(ERROR_DECODER_INIT, "OpusDecoder 初始化失败: " + e.getMessage());
        }
    }

    private byte[] decodeOpus(byte[] opusData, int channels) {
        if (opusDecoderHandle == 0L) {
            Log.e(TAG, "OpusDecoder handle 未初始化");
            return null;
        }

        try {
            int frameSamples = getOpusBridge().getFrameSamples(opusDecoderHandle, false) * channels;
            short[] pcmOut = new short[frameSamples];
            int samplesPerCh = getOpusBridge().decode(opusDecoderHandle, opusData, pcmOut, false);

            if (samplesPerCh <= 0) {
                return null;
            }

            // short[] -> byte[]
            int samples = samplesPerCh * channels;
            byte[] out = new byte[samples * 2];
            int idx = 0;
            for (int i = 0; i < samples; i++) {
                int v = pcmOut[i];
                out[idx++] = (byte) (v & 0xFF);
                out[idx++] = (byte) ((v >> 8) & 0xFF);
            }
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Opus 解码失败", e);
            return null;
        }
    }

    private void releaseOpusDecoder() {
        if (opusDecoderHandle != 0L && opusBridge != null) {
            opusBridge.releaseDecoder(opusDecoderHandle);
            opusDecoderHandle = 0;
        }
    }

    private void releaseAudioTrackInternal() {
        playStarted.set(false);
        playDraining.set(false);

        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            } catch (Throwable ignored) {}
        }

        audioTrack = null;
        currentPlaySampleRate = 0;
        currentPlayChannels = 0;
        pcmQueue.clear();
    }

    private void releasePlayInternal() {
        releaseAudioTrackInternal();
        releaseOpusDecoder();
    }

    private int queueBytes() {
        int sum = 0;
        for (byte[] chunk : pcmQueue) {
            sum += chunk.length;
        }
        return sum;
    }

    private void startDrainingLoop() {
        if (!playDraining.compareAndSet(false, true)) {
            return;
        }

        playExecutor.execute(() -> {
            try {
                while (playStarted.get() && audioTrack != null) {
                    if (pcmQueue.isEmpty()) {
                        int emptyCount = 0;
                        while (pcmQueue.isEmpty() && emptyCount < 5) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignored) {}
                            emptyCount++;
                        }
                        if (pcmQueue.isEmpty()) {
                            break;
                        }
                    }

                    drainQueueNonBlocking(false, 10);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {}
                }
            } finally {
                playDraining.set(false);
            }
        });
    }

    private void drainQueueNonBlocking(boolean preloadOnly, int maxFrames) {
        if (audioTrack == null) return;

        int writtenFrames = 0;

        while (writtenFrames < maxFrames) {
            byte[] chunk = pcmQueue.poll();
            if (chunk == null) break;

            int offset = 0;
            int remaining = chunk.length;

            while (remaining > 0) {
                int res = audioTrack.write(chunk, offset, remaining, AudioTrack.WRITE_NON_BLOCKING);

                if (res < 0) {
                    Log.w(TAG, "AudioTrack write failed: " + res + ", remaining=" + remaining);
                    if (remaining < chunk.length) {
                        byte[] leftover = new byte[remaining];
                        System.arraycopy(chunk, offset, leftover, 0, remaining);
                        pcmQueue.offerFirst(leftover);
                    } else {
                        pcmQueue.offerFirst(chunk);
                    }
                    return;
                } else if (res == 0) {
                    byte[] leftover = new byte[remaining];
                    System.arraycopy(chunk, offset, leftover, 0, remaining);
                    pcmQueue.offerFirst(leftover);
                    return;
                }

                offset += res;
                remaining -= res;
            }

            writtenFrames++;
            if (preloadOnly && writtenFrames >= maxFrames) break;
        }
    }

    private void notifyPlayError(int errorCode, String message) {
        Log.e(TAG, "PlayError[" + errorCode + "]: " + message);
        if (playStateListener != null) {
            playStateListener.onPlayError(errorCode, message);
        }
    }

    // ==================== 静音控制 ====================

    /**
     * 设置麦克风静音状态
     * 静音后采集的音频数据不会通过回调发送
     * @param muted true 为静音，false 为取消静音
     */
    public void setMicMute(boolean muted) {
        this.isMicMuted = muted;
        Log.i(TAG, "设置麦克风静音状态: " + muted);
    }

    /**
     * 获取麦克风静音状态
     */
    public boolean isMicMuted() {
        return isMicMuted;
    }

    /**
     * 设置扬声器静音状态
     * 静音后接收的音频数据不会播放
     * @param muted true 为静音，false 为取消静音
     */
    public void setSpeakerMute(boolean muted) {
        this.isSpeakerMuted = muted;
        Log.i(TAG, "设置扬声器静音状态: " + muted);

        // 如果开启静音，清空播放队列
        if (muted) {
            pcmQueue.clear();
        }
    }

    /**
     * 获取扬声器静音状态
     */
    public boolean isSpeakerMuted() {
        return isSpeakerMuted;
    }

    /**
     * 设置静音状态（同时控制麦克风和扬声器）
     * @param muted true 为静音，false 为取消静音
     */
    public void setMute(boolean muted) {
        setMicMute(muted);
        setSpeakerMute(muted);
    }

    /**
     * 检查是否处于静音状态（麦克风和扬声器都静音）
     */
    public boolean isMuted() {
        return isMicMuted && isSpeakerMuted;
    }

    // ==================== 生命周期管理 ====================

    /**
     * 释放所有资源
     * 调用后需要重新初始化才能使用
     */
    public void release() {
        // 停止录音
        if (isRecording) {
            stopRecord();
        }

        // 释放录音资源
        releaseRecordInternal();

        // 释放播放资源
        releasePlayInternal();
        playExecutor.shutdown();

        isInitialized = false;
        Log.i(TAG, "TalkAudioController 已释放");
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
}
