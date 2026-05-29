package com.whisperonnx;


import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.RecordBuffer;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.ModelIntegrityChecker;

import android.widget.Toast;
import java.io.File;
import java.util.List;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";

    /** Recording mode determines how the mic button and VAD behave. */
    private enum RecordingMode {
        /** Press-and-hold mic: FILTER_SILENCE VAD, stops on release. */
        MANUAL,
        /** Tap A: STOP_ON_SILENCE VAD, one-shot transcription. */
        AUTO,
        /** Tap ∞: CONTINUOUS VAD, hands off utterances on pause, keeps mic open. */
        CONTINUOUS
    }

    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnContinuous;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private CountDownTimer countDownTimer;
    private boolean translate = false;
    private RecordingMode currentMode = RecordingMode.MANUAL;

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        stopAllRecording();
        deinitModel();
        if (mRecorder != null) {
            mRecorder.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (attribute.inputType == EditorInfo.TYPE_NULL) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName + ", fieldId=" + attribute.fieldId);
            stopAllRecording();
            deinitModel();
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){
        if (mWhisper == null) initModel();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        stopAllRecording();
    }

    @Override
    public void onWindowHidden() {
        stopAllRecording();
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        View view = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Whisper_ActionBar))
                .inflate(R.layout.voice_service, null);
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnContinuous = view.findViewById(R.id.btnContinuous);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);

        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        updateModeButtons();
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_LISTENING)) {
                    // Mic is open, waiting for speech
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
                        tvStatus.setText(R.string.listening);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                } else if (message.equals(Recorder.MSG_RECORDING)) {
                    // VAD detected speech
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_recording);
                        tvStatus.setText(R.string.recording);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    // Recording done — start transcription
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> {
                        // In continuous mode, mic stays open so keep red
                        // In auto/manual mode, mic is closed so show purple (processing)
                        int bg = (currentMode == RecordingMode.CONTINUOUS)
                                ? R.drawable.rounded_button_background_recording
                                : R.drawable.rounded_button_background_processing;
                        btnRecord.setBackgroundResource(bg);
                        tvStatus.setText(R.string.processing);
                    });
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer != null) { countDownTimer.cancel(); }
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        processingBar.setProgress(0);
                    });
                }
            }

            @Override
            public void onUtteranceReady() {
                // Continuous mode: an utterance was handed off
                // Mic stays open, so button stays red
                handler.post(() -> {
                    tvStatus.setText(R.string.processing);
                });
                startTranscription();
            }
        });

        // ── Delete button: repeat-delete on long press ──
        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initialDeleteRunnable;
            private Runnable repeatDeleteRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    initialDeleteRunnable = new Runnable() {
                        @Override
                        public void run() {
                            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                            repeatDeleteRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                                    handler.postDelayed(this, 100);
                                }
                            };
                            handler.postDelayed(repeatDeleteRunnable, 100);
                        }
                    };
                    handler.postDelayed(initialDeleteRunnable, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (initialDeleteRunnable != null) {
                        handler.removeCallbacks(initialDeleteRunnable);
                    }
                    if (repeatDeleteRunnable != null) {
                        handler.removeCallbacks(repeatDeleteRunnable);
                    }
                    initialDeleteRunnable = null;
                    repeatDeleteRunnable = null;
                }
                return true;
            }
        });

        // ── Mic button: press-and-hold for manual recording ──
        btnRecord.setOnTouchListener((v, event) -> {
            if (currentMode != RecordingMode.MANUAL) {
                // In auto/continuous mode, mic button is not used for press-and-hold
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening));
                if (checkRecordPermission()) {
                    if (!mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording(Recorder.VadMode.FILTER_SILENCE);
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(30000, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / 300)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                        handler.post(() -> {
                            tvStatus.setText(R.string.listening);
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                    } else {
                        handler.post(() -> {
                            tvStatus.setText(getString(R.string.please_wait));
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

        // ── Keyboard button: switch to previous IME ──
        btnKeyboard.setOnClickListener(v -> {
            stopAllRecording();
            switchToPreviousInputMethod();
        });

        // ── Translate toggle ──
        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        // ── Enter button ──
        btnEnter.setOnClickListener(v -> {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        });

        // ── A button: cycles Manual → Auto → Continuous → Manual ──
        btnModeAuto.setOnClickListener(v -> {
            switch (currentMode) {
                case MANUAL:
                    currentMode = RecordingMode.AUTO;
                    startAutoRecording();
                    break;
                case AUTO:
                    currentMode = RecordingMode.CONTINUOUS;
                    switchToContinuous();
                    break;
                case CONTINUOUS:
                    currentMode = RecordingMode.MANUAL;
                    stopAutoRecording();
                    break;
            }
            updateModeButtons();
        });

        // ── ∞ button: MANUAL→CONTINUOUS, AUTO→CONTINUOUS, CONTINUOUS→AUTO ──
        btnContinuous.setOnClickListener(v -> {
            if (currentMode == RecordingMode.CONTINUOUS) {
                currentMode = RecordingMode.AUTO;
                switchToAuto();
            } else if (currentMode == RecordingMode.AUTO) {
                currentMode = RecordingMode.CONTINUOUS;
                switchToContinuous();
            } else {
                // MANUAL → CONTINUOUS (skips AUTO, enables both)
                currentMode = RecordingMode.CONTINUOUS;
                switchToContinuous();
            }
            updateModeButtons();
        });

        return view;
    }

    // ── Mode button state ──

    private void updateModeButtons() {
        handler.post(() -> {
            // Guard: view references may be null if called before onCreateInputView()
            // (e.g. from onFinishInputView / onWindowHidden during initial lifecycle).
            if (btnModeAuto == null) return;
            switch (currentMode) {
                case MANUAL:
                    btnModeAuto.setImageResource(R.drawable.ic_auto_off_36dp);
                    btnContinuous.setVisibility(View.VISIBLE);
                    btnContinuous.setImageResource(R.drawable.ic_continuous_off_36dp);
                    btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                    tvStatus.setVisibility(View.INVISIBLE);
                    break;
                case AUTO:
                    btnModeAuto.setImageResource(R.drawable.ic_auto_on_36dp);
                    btnContinuous.setVisibility(View.VISIBLE);
                    btnContinuous.setImageResource(R.drawable.ic_continuous_off_36dp);
                    break;
                case CONTINUOUS:
                    btnModeAuto.setImageResource(R.drawable.ic_auto_on_36dp);
                    btnContinuous.setVisibility(View.VISIBLE);
                    btnContinuous.setImageResource(R.drawable.ic_continuous_on_36dp);
                    break;
            }
        });
    }

    // ── Recording lifecycle ──

    private void startRecording(Recorder.VadMode vadMode) {
        mRecorder.initVad(vadMode);
        mRecorder.start();
    }

    private void startAutoRecording() {
        HapticFeedback.vibrate(this);
        startRecording(Recorder.VadMode.STOP_ON_SILENCE);
        handler.post(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
            tvStatus.setText(R.string.listening);
            tvStatus.setVisibility(View.VISIBLE);
            processingBar.setProgress(100);
        });
        countDownTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long l) {
                handler.post(() -> processingBar.setProgress((int) (l / 300)));
            }
            @Override
            public void onFinish() {}
        };
        countDownTimer.start();
    }

    private void switchToContinuous() {
        // Stop current auto recording and restart in continuous mode
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        if (mWhisper != null) mWhisper.stop();
        HapticFeedback.vibrate(this);
        startRecording(Recorder.VadMode.CONTINUOUS);
        handler.post(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
            tvStatus.setText(R.string.listening);
            tvStatus.setVisibility(View.VISIBLE);
            processingBar.setProgress(100);
        });
        countDownTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long l) {
                handler.post(() -> processingBar.setProgress((int) (l / 300)));
            }
            @Override
            public void onFinish() {}
        };
        countDownTimer.start();
    }

    private void switchToAuto() {
        // Stop continuous recording and restart in auto (one-shot) mode
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        if (mWhisper != null) mWhisper.stop();
        HapticFeedback.vibrate(this);
        startRecording(Recorder.VadMode.STOP_ON_SILENCE);
        handler.post(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
            tvStatus.setText(R.string.listening);
            tvStatus.setVisibility(View.VISIBLE);
            processingBar.setProgress(100);
        });
        countDownTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long l) {
                handler.post(() -> processingBar.setProgress((int) (l / 300)));
            }
            @Override
            public void onFinish() {}
        };
        countDownTimer.start();
    }

    private void stopAutoRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        if (mWhisper != null) stopTranscription();
        if (countDownTimer != null) { countDownTimer.cancel(); }
        handler.post(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
            tvStatus.setVisibility(View.INVISIBLE);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(false);
        });
    }

    /**
     * Stops all recording and resets to MANUAL mode.
     * Called from lifecycle callbacks (onFinishInputView, onWindowHidden, etc.)
     * to ensure the mic is never active when the user is not looking at a text field.
     */
    private void stopAllRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        if (mWhisper != null) stopTranscription();
        currentMode = RecordingMode.MANUAL;
        updateModeButtons();
        if (countDownTimer != null) { countDownTimer.cancel(); }
        handler.post(() -> {
            if (btnRecord == null) return; // not yet inflated
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
            tvStatus.setVisibility(View.INVISIBLE);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(false);
        });
    }

    // ── Model initialization ──

    private void initModel() {
        mWhisper = new Whisper(this);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> processingBar.setIndeterminate(false));

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }

                boolean commitSuccess = false;
                if (result.trim().length() > 0) {
                    commitSuccess = getCurrentInputConnection().commitText(result.trim() + " ", 1);
                }

                if (currentMode == RecordingMode.CONTINUOUS) {
                    // Continuous mode: don't stop — the next utterance is already being recorded.
                    // Button stays red (mic is still open).
                    // Drain any utterances that arrived in the queue while we were transcribing
                    // this one.  Whisper.start() silently no-ops when in-progress, so queued
                    // utterances would otherwise be lost; checking here ensures every enqueued
                    // utterance triggers a transcription pass.
                    if (RecordBuffer.hasUtterances()) {
                        startTranscription();
                    }
                    handler.post(() -> {
                        processingBar.setProgress(0);
                        processingBar.setIndeterminate(false);
                    });
                } else if (currentMode == RecordingMode.AUTO) {
                    // Auto one-shot: we're done. Reset to manual mode.
                    // Do NOT switch away from the keyboard — stay on WhisperVault.
                    currentMode = RecordingMode.MANUAL;
                    updateModeButtons();
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        tvStatus.setVisibility(View.INVISIBLE);
                    });
                }
                // MANUAL mode: button is already reset by the touch-up handler
            }
        });
        if (mWhisper.isModelReady()) {
            new Thread(() -> {
                File modelDir = getExternalFilesDir(null);
                String modelName = ModelIntegrityChecker.getSelectedModel(WhisperInputMethodService.this);
                List<String> mismatches = ModelIntegrityChecker.getMismatches(modelDir, modelName);
                if (!mismatches.isEmpty()) {
                    Log.w(TAG, "Model integrity check failed: " + mismatches);
                    handler.post(() -> Toast.makeText(mContext,
                            "Warning: model integrity check failed — files may be modified.",
                            Toast.LENGTH_LONG).show());
                }
                Whisper w = mWhisper;
                if (w != null) handler.post(w::loadModel);
            }, "integrity-check").start();
        }
    }

    private void startTranscription() {
        if (countDownTimer != null) { countDownTimer.cancel(); }
        handler.post(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        if (mWhisper != null) {
            if (translate) mWhisper.setAction(ACTION_TRANSLATE);
            else mWhisper.setAction(ACTION_TRANSCRIBE);

            String langCode = sp.getString("language", "auto");
            Log.d("WhisperIME", "default langCode " + langCode);
            mWhisper.setLanguage(langCode);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
        handler.post(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }
}