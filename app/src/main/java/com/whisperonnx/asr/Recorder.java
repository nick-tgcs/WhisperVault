package com.whisperonnx.asr;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.VisibleForTesting;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whisperonnx.R;

import java.io.ByteArrayOutputStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    /**
     * When non-null, {@link #recordAudio()} skips {@link AudioRecord} entirely and feeds
     * these raw s16le 16 kHz mono PCM bytes directly into {@link RecordBuffer}.
     * Set this in instrumented tests before triggering a recording.
     * Clear it in {@code @After} to avoid leaking between tests.
     */
    @VisibleForTesting
    public static volatile byte[] sTestAudioBytes = null;

    public interface RecorderListener {
        void onUpdateReceived(String message);
        /**
         * Called in {@link VadMode#CONTINUOUS} mode when a pause is detected after
         * speech. The current utterance has been enqueued via
         * {@link RecordBuffer#enqueueUtterance(byte[])} and the mic remains open
         * for the next utterance.
         */
        void onUtteranceReady();
    }

    /**
     * Determines how VAD is used during recording.
     * <ul>
     *   <li>{@link #OFF} – VAD is not used; all audio is written to the buffer.</li>
     *   <li>{@link #FILTER_SILENCE} – VAD filters out silence so that pauses
     *       do not consume the 30-second buffer. Recording continues until the
     *       user manually stops or the buffer fills with speech.</li>
     *   <li>{@link #STOP_ON_SILENCE} – VAD stops recording automatically after
     *       the configured silence duration (auto mode).</li>
     *   <li>{@link #CONTINUOUS} – VAD filters out silence and hands off each
     *       utterance on pause, but keeps the mic open. The recording loop
     *       continues until explicitly stopped. Each utterance is enqueued via
     *       {@link RecordBuffer#enqueueUtterance(byte[])} and the listener is
     *       notified via {@link RecorderListener#onUtteranceReady()}.</li>
     * </ul>
     */
    public enum VadMode {
        OFF,
        FILTER_SILENCE,
        STOP_ON_SILENCE,
        CONTINUOUS
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";
    public static final String MSG_LISTENING = "Listening...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object(); // Lock object for wait/notify

    private volatile boolean shouldStartRecording = false;
    private VadMode vadMode = VadMode.OFF;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;
    private SharedPreferences sp;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
        sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }


    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initialises VAD in {@link VadMode#STOP_ON_SILENCE} mode (auto mode).
     * Equivalent to {@code initVad(VadMode.STOP_ON_SILENCE)}.
     */
    public void initVad() {
        initVad(VadMode.STOP_ON_SILENCE);
    }

    /**
     * Initialises VAD with the given mode.
     *
     * @param mode {@link VadMode#FILTER_SILENCE} to skip silence without stopping,
     *             {@link VadMode#STOP_ON_SILENCE} to stop recording on silence,
     *             or {@link VadMode#CONTINUOUS} to hand off utterances on silence
     *             and keep the mic open.
     */
    public void initVad(VadMode mode) {
        int silenceDurationMs = sp.getInt("silenceDurationMs", 800);
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(silenceDurationMs)
                .setSpeechDurationMs(200)
                .build();
        vadMode = mode;
        Log.d(TAG, "VAD initialized in mode: " + mode);
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish.
        // Use a timeout so that if the worker already called notify() before we
        // reach wait() (a race that can happen in tests where recording finishes
        // almost instantly), we don't block forever.
        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    /**
     * Interrupts the background worker thread, causing it to exit its loop and
     * release any held resources.  Must be called when the owning component is
     * destroyed (e.g. {@code onDestroy}) to prevent thread leaks.
     */
    public void destroy() {
        workerThread.interrupt();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }


    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        // --- Test injection hook -------------------------------------------
        // If a test has pre-loaded PCM bytes, skip AudioRecord entirely.
        if (sTestAudioBytes != null) {
            byte[] testBytes = sTestAudioBytes;
            RecordBuffer.setOutputBuffer(testBytes);
            sendUpdate(testBytes.length > 6400 ? MSG_RECORDING_DONE : MSG_RECORDING_ERROR);
            synchronized (fileSavedLock) {
                fileSavedLock.notify();
            }
            return;
        }
        // ------------------------------------------------------------------

        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (sp.getBoolean("bluetooth", false)){
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        // Calculate maximum byte counts for 30 seconds (for saving)
        int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;
        int speechBytesWritten = 0;  // Only counts speech bytes (used for 30s limit when VAD filters silence)

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  //VAD needs 16 bit

        while (mInProgress.get()) {
            // In FILTER_SILENCE/CONTINUOUS modes, the 30s limit applies to speech bytes only.
            // In other modes, it applies to total bytes read.
            int bytesLimit = (vadMode == VadMode.FILTER_SILENCE || vadMode == VadMode.CONTINUOUS) ? speechBytesWritten : totalBytesRead;
            if (bytesLimit >= bytesForThirtySeconds) break;

            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                totalBytesRead += bytesRead;
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            if (vadMode != VadMode.OFF) {
                // Use the most recently read frame for VAD detection
                System.arraycopy(audioData, 0, vadAudioBuffer, 0, Math.min(bytesRead, VAD_FRAME_SIZE * 2));

                isSpeech = vad.isSpeech(vadAudioBuffer);
                if (isSpeech) {
                    if (!isRecording) {
                        Log.d(TAG, "VAD Speech detected: recording starts");
                        sendUpdate(MSG_RECORDING);
                    }
                    isRecording = true;
                    outputBuffer.write(audioData, 0, bytesRead);  // Only write speech to buffer
                    speechBytesWritten += bytesRead;
                } else {
                    if (isRecording) {
                        isRecording = false;
                        if (vadMode == VadMode.STOP_ON_SILENCE) {
                            mInProgress.set(false);
                        } else if (vadMode == VadMode.CONTINUOUS) {
                            // Hand off the current utterance and reset for the next one
                            byte[] utteranceBytes = outputBuffer.toByteArray();
                            if (utteranceBytes.length > 6400) {  // min 0.2s
                                RecordBuffer.enqueueUtterance(utteranceBytes);
                                if (mListener != null) mListener.onUtteranceReady();
                            } else if (utteranceBytes.length > 0) {
                                Log.d(TAG, "Utterance too short (" + utteranceBytes.length + " bytes), discarding");
                            }
                            outputBuffer.reset();
                            speechBytesWritten = 0;
                            sendUpdate(MSG_LISTENING);
                        }
                    }
                    // In FILTER_SILENCE mode, silence frames are simply not written
                    // to the buffer, so they don't consume the 30-second limit.
                }
            } else {
                outputBuffer.write(audioData, 0, bytesRead);  // Save all bytes read up to 30 seconds
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes read: " + totalBytesRead + ", speech bytes written: " + speechBytesWritten);

        // Capture the mode before resetting it, so we can use it for the hand-off logic below.
        VadMode modeAtEnd = vadMode;

        if (vadMode != VadMode.OFF) {
            vadMode = VadMode.OFF;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        // Save recorded audio data to RecordBuffer
        byte[] outputBytes = outputBuffer.toByteArray();

        if (modeAtEnd == VadMode.CONTINUOUS) {
            // CONTINUOUS mode: utterances were handed off via enqueueUtterance during the loop.
            // If there's a partial utterance remaining (loop was stopped mid-speech), hand it off.
            if (outputBytes.length > 6400) {
                RecordBuffer.enqueueUtterance(outputBytes);
                if (mListener != null) mListener.onUtteranceReady();
            }
            // Signal that recording is fully done (loop exited)
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            // FILTER_SILENCE / STOP_ON_SILENCE / OFF: single-buffer mode
            RecordBuffer.setOutputBuffer(outputBytes);
            if (outputBytes.length > 6400){  //min 0.2s
                sendUpdate(MSG_RECORDING_DONE);
            } else {
                sendUpdate(MSG_RECORDING_ERROR);
            }
        }

        // Notify the waiting thread that recording is complete
        synchronized (fileSavedLock) {
            fileSavedLock.notify(); // Notify that recording is finished
        }

    }

}
