package com.videocutter;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Trims (and optionally crops) a video using only Android framework APIs.
 *
 * Two paths are used:
 *
 * 1. Transmux: MediaExtractor to MediaMuxer, no re-encoding, no quality loss. Used when there is
 *    no crop and the start time is on (or within a few milliseconds of) a keyframe.
 *
 * 2. Transcode: MediaCodec decoder to OpenGL to MediaCodec H.264 encoder. Used when a crop is set,
 *    or when the start time is not on a keyframe so that the cut is frame accurate. Audio is copied
 *    without re-encoding.
 *
 * Call export() from a background thread.
 */
public final class VideoExporter {

    public interface ProgressListener {
        /** Called from the export thread with 0..100. */
        void onProgress(int percent);
    }

    private static final long SYNC_TOLERANCE_US = 60000L;
    private static final long TIMEOUT_US = 10000L;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private VideoExporter() {
    }

    /**
     * @param cropFractions null for no crop, otherwise {left, top, right, bottom} as fractions
     *                      (0..1) of the displayed frame, origin at the top left.
     */
    public static void export(Context context, Uri source, File output,
                              long startMs, long endMs, float[] cropFractions,
                              ProgressListener listener) throws Exception {
        long startUs = startMs * 1000L;
        long endUs = endMs * 1000L;
        if (endUs <= startUs) throw new Exception("Invalid trim range");

        int videoTrack = -1;
        int audioTrack = -1;
        boolean needTranscode = false;
        long syncUs = startUs;

        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(context, source, null);
            for (int i = 0; i < probe.getTrackCount(); i++) {
                String mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i;
                else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i;
            }
            if (videoTrack < 0 && audioTrack < 0) {
                throw new Exception("No audio or video track found");
            }
            if (videoTrack >= 0) {
                if (cropFractions != null) {
                    needTranscode = true;
                } else {
                    probe.selectTrack(videoTrack);
                    probe.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    long t = probe.getSampleTime();
                    if (t >= 0L) {
                        syncUs = t;
                        if (startUs - t > SYNC_TOLERANCE_US) needTranscode = true;
                    }
                }
            }
        } finally {
            probe.release();
        }

        int rotation = readRotation(context, source);
        if (needTranscode) {
            transcode(context, source, output, startUs, endUs, rotation,
                    cropFractions, videoTrack, audioTrack, listener);
        } else {
            transmux(context, source, output, startUs, endUs, syncUs, rotation, listener);
        }
        if (listener != null) listener.onProgress(100);
    }

    // ------------------------------------------------------------------
    // Path 1: lossless transmux
    // ------------------------------------------------------------------

    private static void transmux(Context context, Uri source, File output,
                                 long startUs, long endUs, long syncUs, int rotation,
                                 ProgressListener listener) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            ex.setDataSource(context, source, null);
            int n = ex.getTrackCount();
            int[] trackMap = new int[n];
            int maxSize = 256 * 1024;
            boolean hasVideo = false;
            int added = 0;

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            for (int i = 0; i < n; i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                trackMap[i] = -1;
                if (mime == null) continue;
                boolean isVideo = mime.startsWith("video/");
                if (isVideo || mime.startsWith("audio/")) {
                    trackMap[i] = muxer.addTrack(fmt);
                    ex.selectTrack(i);
                    added++;
                    if (isVideo) hasVideo = true;
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSize = Math.max(maxSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                }
            }
            if (added == 0) throw new Exception("No audio or video track found");
            if (hasVideo && rotation != 0) muxer.setOrientationHint(rotation);

            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long baseUs = hasVideo ? Math.min(syncUs, startUs) : startUs;

            ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            muxer.start();
            started = true;

            long span = Math.max(1L, endUs - startUs);
            while (true) {
                long t = ex.getSampleTime();
                if (t < 0L || t > endUs) break;
                int track = ex.getSampleTrackIndex();
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (t >= baseUs && track >= 0 && trackMap[track] >= 0) {
                    int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
                    info.set(0, size, t - baseUs, flags);
                    muxer.writeSampleData(trackMap[track], buf, info);
                }
                ex.advance();
                if (listener != null) {
                    long p = (t - startUs) * 100L / span;
                    listener.onProgress((int) Math.max(0L, Math.min(99L, p)));
                }
            }
        } finally {
            ex.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Path 2: decode, crop with OpenGL, encode
    // ------------------------------------------------------------------

    private static final String VERTEX_SHADER =
            "uniform mat4 uSTMatrix;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTextureCoord;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPosition;\n"
                    + "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n"
                    + "}\n";

    /** Signals from SurfaceTexture.onFrameAvailable (delivered on the main thread). */
    private static final class FrameSync implements SurfaceTexture.OnFrameAvailableListener {
        private boolean available = false;

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture st) {
            available = true;
            notifyAll();
        }

        synchronized boolean await(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!available) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) return false;
                wait(left);
            }
            available = false;
            return true;
        }
    }

    private static void transcode(Context context, Uri source, File output,
                                  long startUs, long endUs, int rotation,
                                  float[] crop, int videoTrack, int audioTrack,
                                  ProgressListener listener) throws Exception {
        if (videoTrack < 0) throw new Exception("No video track to transcode");

        float cl = 0f;
        float ct = 0f;
        float cr = 1f;
        float cb = 1f;
        if (crop != null && crop.length >= 4) {
            cl = crop[0];
            ct = crop[1];
            cr = crop[2];
            cb = crop[3];
        }

        MediaExtractor ex = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface encoderSurface = null;
        Surface decoderSurface = null;
        SurfaceTexture surfaceTexture = null;
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        boolean muxerStarted = false;

        try {
            ex.setDataSource(context, source, null);
            MediaFormat videoFormat = ex.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioTrack >= 0 ? ex.getTrackFormat(audioTrack) : null;
            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);

            int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            boolean swap = rotation == 90 || rotation == 270;
            int dispW = swap ? srcH : srcW;
            int dispH = swap ? srcW : srcH;

            int outW = ((int) Math.round((cr - cl) * dispW)) & ~1;
            int outH = ((int) Math.round((cb - ct) * dispH)) & ~1;
            outW = Math.max(16, outW);
            outH = Math.max(16, outH);

            int bitrate = chooseBitrate(context, source, (long) srcW * srcH, (long) outW * outH);
            int frameRate = readFrameRate(videoFormat);

            // Encoder
            MediaFormat encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH);
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            // EGL bound to the encoder input surface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new Exception("No EGL display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new Exception("EGL init failed");
            }
            int[] configAttribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                throw new Exception("No suitable EGL config");
            }
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
                    encoderSurface, surfaceAttribs, 0);
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new Exception("eglMakeCurrent failed");
            }

            // GL program and external texture
            int program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            int aTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
            int uStMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            FrameSync frameSync = new FrameSync();
            surfaceTexture = new SurfaceTexture(tex[0]);
            surfaceTexture.setOnFrameAvailableListener(frameSync);
            decoderSurface = new Surface(surfaceTexture);

            FloatBuffer positions = toBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            FloatBuffer texCoords = toBuffer(buildTexCoords(cl, ct, cr, cb, rotation));
            float[] stMatrix = new float[16];

            // Decoder outputs unrotated frames; rotation is applied by the texture coordinates
            videoFormat.setInteger("rotation-degrees", 0);
            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(videoFormat, decoderSurface, null, 0);
            decoder.start();

            ex.selectTrack(videoTrack);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            int videoOutTrack = -1;
            int audioOutTrack = -1;
            int framesWritten = 0;
            long span = Math.max(1L, endUs - startUs);

            while (!encoderDone) {
                // 1. Feed the decoder
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(1000L);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int size = ex.readSampleData(inBuf, 0);
                        long t = ex.getSampleTime();
                        if (size < 0 || t < 0L || t > endUs) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, t, 0);
                            ex.advance();
                        }
                    }
                }

                // 2. Drain the decoder and render wanted frames through OpenGL
                if (!decoderDone) {
                    int outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIdx >= 0) {
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = decInfo.presentationTimeUs;
                        boolean render = decInfo.size > 0 && pts >= startUs && pts <= endUs;
                        decoder.releaseOutputBuffer(outIdx, render);
                        if (render) {
                            if (!frameSync.await(5000L)) {
                                throw new Exception("Timed out waiting for a decoded frame");
                            }
                            surfaceTexture.updateTexImage();
                            surfaceTexture.getTransformMatrix(stMatrix);

                            GLES20.glViewport(0, 0, outW, outH);
                            GLES20.glClearColor(0f, 0f, 0f, 1f);
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                            GLES20.glUseProgram(program);
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
                            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0);
                            GLES20.glEnableVertexAttribArray(aPosition);
                            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT,
                                    false, 8, positions);
                            GLES20.glEnableVertexAttribArray(aTexCoord);
                            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT,
                                    false, 8, texCoords);
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                            GLES20.glDisableVertexAttribArray(aPosition);
                            GLES20.glDisableVertexAttribArray(aTexCoord);

                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                                    (pts - startUs) * 1000L);
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                            if (listener != null) {
                                long p = (pts - startUs) * 100L / span;
                                listener.onProgress((int) Math.max(0L, Math.min(99L, p)));
                            }
                        }
                        if (eos) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                // 3. Drain the encoder into the muxer
                while (true) {
                    int encIdx = encoder.dequeueOutputBuffer(encInfo, decoderDone ? TIMEOUT_US : 0L);
                    if (encIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new Exception("Encoder format changed twice");
                        videoOutTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) audioOutTrack = muxer.addTrack(audioFormat);
                        muxer.start();
                        muxerStarted = true;
                    } else if (encIdx >= 0) {
                        ByteBuffer encBuf = encoder.getOutputBuffer(encIdx);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encInfo.size = 0;
                        }
                        if (encInfo.size > 0 && muxerStarted && encBuf != null) {
                            encBuf.position(encInfo.offset);
                            encBuf.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(videoOutTrack, encBuf, encInfo);
                            framesWritten++;
                        }
                        encoder.releaseOutputBuffer(encIdx, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }
            }

            if (!muxerStarted || framesWritten == 0) {
                throw new Exception("No video frames were written");
            }

            // 4. Copy the audio track without re-encoding
            if (audioTrack >= 0 && audioOutTrack >= 0) {
                copyAudio(context, source, muxer, audioTrack, audioOutTrack,
                        audioFormat, startUs, endUs);
            }
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
            if (decoderSurface != null) decoderSurface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            if (encoderSurface != null) encoderSurface.release();
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void copyAudio(Context context, Uri source, MediaMuxer muxer,
                                  int audioTrack, int audioOutTrack, MediaFormat audioFormat,
                                  long startUs, long endUs) throws Exception {
        MediaExtractor ax = new MediaExtractor();
        try {
            ax.setDataSource(context, source, null);
            ax.selectTrack(audioTrack);
            ax.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int maxSize = 256 * 1024;
            if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxSize = Math.max(maxSize, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (true) {
                long t = ax.getSampleTime();
                if (t < 0L || t > endUs) break;
                if (t < startUs) {
                    ax.advance();
                    continue;
                }
                buf.clear();
                int size = ax.readSampleData(buf, 0);
                if (size < 0) break;
                info.set(0, size, t - startUs, MediaCodec.BUFFER_FLAG_SYNC_FRAME);
                muxer.writeSampleData(audioOutTrack, buf, info);
                ax.advance();
            }
        } finally {
            ax.release();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Texture coordinates for the four quad corners (bottom left, bottom right, top left,
     * top right) that sample the crop rectangle of the displayed frame, undoing the video's
     * rotation metadata. The SurfaceTexture transform matrix is applied in the shader.
     */
    static float[] buildTexCoords(float cl, float ct, float cr, float cb, int rotation) {
        float[] xs = {-1f, 1f, -1f, 1f};
        float[] ys = {-1f, -1f, 1f, 1f};
        float[] out = new float[8];
        for (int k = 0; k < 4; k++) {
            float fx = (xs[k] + 1f) / 2f;
            float fy = (1f - ys[k]) / 2f;
            float dx = cl + fx * (cr - cl);
            float dy = ct + fy * (cb - ct);
            float sx;
            float sy;
            switch (rotation) {
                case 90:
                    sx = dy;
                    sy = 1f - dx;
                    break;
                case 180:
                    sx = 1f - dx;
                    sy = 1f - dy;
                    break;
                case 270:
                    sx = 1f - dy;
                    sy = dx;
                    break;
                default:
                    sx = dx;
                    sy = dy;
                    break;
            }
            out[k * 2] = sx;
            out[k * 2 + 1] = 1f - sy;
        }
        return out;
    }

    private static FloatBuffer toBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int createProgram(String vertexSrc, String fragmentSrc) throws Exception {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new Exception("Could not link GL program: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String src) throws Exception {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new Exception("Could not compile shader: " + log);
        }
        return shader;
    }

    private static int readRotation(Context context, Uri source) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, source);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (s != null) {
                int deg = Integer.parseInt(s) % 360;
                if (deg < 0) deg += 360;
                if (deg == 90 || deg == 180 || deg == 270) return deg;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static int chooseBitrate(Context context, Uri source, long srcPixels, long outPixels) {
        long srcBitrate = 0L;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, source);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (s != null) srcBitrate = Long.parseLong(s);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        long bitrate;
        if (srcBitrate > 0L && srcPixels > 0L) {
            bitrate = (long) (srcBitrate * ((double) outPixels / (double) srcPixels) * 1.25);
        } else {
            bitrate = outPixels * 4L;
        }
        bitrate = Math.max(1000000L, Math.min(40000000L, bitrate));
        return (int) bitrate;
    }

    private static int readFrameRate(MediaFormat f) {
        try {
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    return Math.max(1, f.getInteger(MediaFormat.KEY_FRAME_RATE));
                } catch (ClassCastException e) {
                    return Math.max(1, Math.round(f.getFloat(MediaFormat.KEY_FRAME_RATE)));
                }
            }
        } catch (Exception ignored) {
        }
        return 30;
    }
}
