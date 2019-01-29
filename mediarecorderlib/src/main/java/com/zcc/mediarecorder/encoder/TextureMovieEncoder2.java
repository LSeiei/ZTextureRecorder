/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zcc.mediarecorder.encoder;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import com.zcc.mediarecorder.ALog;
import com.zcc.mediarecorder.common.ILifeCircle;
import com.zcc.mediarecorder.encoder.video.IVideoEncoderCore;
import com.zcc.mediarecorder.encoder.video.MediaCodecEncoderCore;

import java.io.IOException;
import java.lang.ref.WeakReference;

import static com.zcc.mediarecorder.encoder.TextureMovieEncoder2.EncoderType.MEDIA_CODEC;

public class TextureMovieEncoder2 implements Runnable, ILifeCircle {
    private static final String TAG = "TextureMovieEncoder2";
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FRAME_AVAILABLE = 2;
    private final Object mReadyFence = new Object();      // guards ready/running
    private IVideoEncoderCore mVideoEncoder;
    private volatile EncoderHandler mHandler;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isPrepared = false;
    private boolean mReady;
    private boolean mRunning;

    /**
     * Tells the video recorder to start recording.  (Call from non-encoderType thread.)
     * <p>
     * Creates a new thread, which will own the provided MediaCodecEncoderCore.  When the
     * thread exits, the MediaCodecEncoderCore will be released.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.
     */
    public TextureMovieEncoder2(int width, int height, String outputFile, EncoderType encoderType) {
        Log.d(TAG, "EncoderType: startRecording()");
        if (encoderType == null) {
            encoderType = MEDIA_CODEC;
        }
        switch (encoderType) {
            case MEDIA_CODEC:
                try {
                    mVideoEncoder = new MediaCodecEncoderCore(width, height, outputFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                isPrepared = true;
                break;
            case MEDIA_RECORDER:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mVideoEncoder = new MediaRecorderEncoderCore(width, height, outputFile);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mVideoEncoder.prepare();
                            isPrepared = true;
                        }
                    });
                }
                break;
            default:
                throw new IllegalStateException("unexpected encoderType type");
        }
        synchronized (mReadyFence) {
            if (mRunning) {
                Log.w(TAG, "EncoderType thread already running");
                return;
            }
            mRunning = true;
            while (!isPrepared) {
            }
            new Thread(this, "TextureMovieEncoder").start();
            while (!mReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }
    }

    public Surface getRecordSurface() {
        return mVideoEncoder.getInputSurface();
    }

    public long getPTSUs() {
        return mVideoEncoder.getPTSUs();
    }

    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    public void frameAvailableSoon() {
        synchronized (mReadyFence) {
            if (!mReady) {
                return;
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE));
    }

    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new EncoderHandler(this);
            mVideoEncoder.prepare();
            mReady = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "EncoderType thread exiting");
        synchronized (mReadyFence) {
            mReady = mRunning = false;
            mHandler = null;
        }
    }

    private void handleFrameAvailable() {
        ALog.dd("handleFrameAvailable");
        mVideoEncoder.drainEncoder(false);
    }

    private void handleStopRecording() {
        ALog.dd("handleStopRecording");
        mVideoEncoder.drainEncoder(true);
        mVideoEncoder.release();
    }

    @Override
    public void start() {
        mVideoEncoder.start();
    }

    @Override
    public void stop() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
    }

    @Override
    public void release() {

    }

    @Override
    public void prepare() {

    }

    public enum EncoderType {
        MEDIA_RECORDER,
        MEDIA_CODEC
    }

    private static class EncoderHandler extends Handler {
        private WeakReference<TextureMovieEncoder2> mWeakEncoder;

        public EncoderHandler(TextureMovieEncoder2 encoder) {
            mWeakEncoder = new WeakReference<>(encoder);
        }

        @Override  // runs on encoder thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            TextureMovieEncoder2 encoder = mWeakEncoder.get();
            if (encoder == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
                    Looper.myLooper().quit();
                    break;
                case MSG_FRAME_AVAILABLE:
                    encoder.handleFrameAvailable();
                    break;
                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }
}