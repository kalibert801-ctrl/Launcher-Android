package com.winlator.alsaserver;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private byte channelCount = 2;
    private int sampleRate = 0;
    private int position;
    private int bufferSize;
    private int frameBytes;
    private ByteBuffer sharedBuffer;
    private boolean playing = false;
    private long streamPtr = 0;

    static {
        System.loadLibrary("winlator");
    }

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        stop(streamPtr);
        close(streamPtr);
        playing = false;
        streamPtr = 0;
    }

    /** Поток AAudio сломался (смена наушников/динамика, ошибка) — открываем новый, звук не пропадает. */
    private void recreateStream(int code) {
        if (System.currentTimeMillis() - lastRecreate < 1000) return;
        lastRecreate = System.currentTimeMillis();
        if (streamPtr > 0) { stop(streamPtr); close(streamPtr); }
        playing = false;
        streamPtr = create(dataType.ordinal(), channelCount, sampleRate, bufferSize);
        com.winlator.xserver.XDebug.audio("поток AAudio сломался (код " + code + ") — " +
            (streamPtr > 0 ? "открыт заново: " + getBufferInfo(streamPtr) : "открыть заново не удалось"));
        if (streamPtr > 0) start();
    }

    private long lastRecreate;

    private DataType streamType;
    private byte streamChannels;
    private int streamRate;

    public void prepare() {
        position = 0;
        frameBytes = channelCount * dataType.byteCount;

        // AleksOS: Wine вызывает prepare после каждой заминки. Если формат тот же — оставляем
        // открытый поток AAudio (пересоздание давало щелчок и провал звука)
        if (streamPtr > 0 && dataType == streamType && channelCount == streamChannels && sampleRate == streamRate) {
            if (sharedBuffer != null) {
                SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
                sharedBuffer = null;
            }
            reprepares++;
            start();
            return;
        }
        release();

        if (!isValidBufferSize()) return;

        streamPtr = create(dataType.ordinal(), channelCount, sampleRate, bufferSize);
        streamType = dataType; streamChannels = channelCount; streamRate = sampleRate;
        com.winlator.xserver.XDebug.audio(dataType + ", " + sampleRate + " Гц, каналов " + channelCount +
            ", буфер Wine " + bufferSize + " кадров" + (streamPtr > 0 ? ", AAudio " + getBufferInfo(streamPtr) : ", AAudio НЕ открылся"));
        statTime = System.currentTimeMillis(); statFrames = 0; statWriteMs = 0; statMaxWriteMs = 0;
        if (streamPtr > 0) start();
    }

    public void start() {
        if (streamPtr > 0 && !playing) {
            start(streamPtr);
            playing = true;
        }
    }

    public void stop() {
        if (streamPtr > 0 && playing) {
            stop(streamPtr);
            playing = false;
        }
    }

    public void pause() {
        if (streamPtr > 0) {
            pause(streamPtr);
            playing = false;
        }
    }

    public void drain() {
        if (streamPtr > 0) flush(streamPtr);
    }

    public void writeDataToStream(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        }
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }

        if (playing) {
            int numFrames = data.limit() / frameBytes;
            long t0 = System.nanoTime();
            int framesWritten = write(streamPtr, data, numFrames);
            long ms = (System.nanoTime() - t0) / 1000000;
            if (framesWritten > 0) position += framesWritten;
            else if (framesWritten < 0) recreateStream(framesWritten);
            // раз в 5 секунд: сколько звука пришло от игры за это время и сколько раз AAudio оставался без звука
            statFrames += Math.max(framesWritten, 0); statWriteMs += ms; statMaxWriteMs = Math.max(statMaxWriteMs, ms);
            long now = System.currentTimeMillis();
            if (now - statTime >= 5000) {
                int pct = (int)(statFrames * 100L * 1000 / Math.max(1, (now - statTime)) / Math.max(1, sampleRate));
                com.winlator.xserver.XDebug.audio("за " + (now - statTime) / 1000 + " с пришло " + pct + "% нужного звука, провалов " +
                    getXRuns(streamPtr) + ", перезапусков Wine " + reprepares + ", запись ждала " + statWriteMs + " мс (макс " + statMaxWriteMs + ")");
                statTime = now; statFrames = 0; statWriteMs = 0; statMaxWriteMs = 0; reprepares = 0;
            }
            data.rewind();
        }
    }

    public int pointer() {
        return position;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = (byte)channelCount;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        this.sharedBuffer = sharedBuffer;
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return (getBufferSizeInBytes() % frameBytes == 0) && bufferSize > 0;
    }

    public int computeLatencyMillis() {
        return (int)(((float)bufferSize / sampleRate) * 1000);
    }

    private long statTime, statFrames, statWriteMs, statMaxWriteMs;
    private int reprepares;

    private native int getXRuns(long streamPtr);

    private native String getBufferInfo(long streamPtr);

    private native long create(int format, byte channelCount, int sampleRate, int bufferSize);

    private native int write(long streamPtr, ByteBuffer buffer, int numFrames);

    private native void start(long streamPtr);

    private native void stop(long streamPtr);

    private native void pause(long streamPtr);

    private native void flush(long streamPtr);

    private native void close(long streamPtr);
}
