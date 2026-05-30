package com.whisperonnx;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.RecordBuffer;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.ModelIntegrityChecker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final String TAG = "WhisperRecognizeActivity";

    /** Recording mode — mirrors WhisperInputMethodService.RecordingMode. */
    enum RecordingMode { MANUAL, AUTO, CONTINUOUS }

    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ImageButton btnContinuous;
    private TextView tvStatus;
    private ProgressBar processingBar = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private AlertDialog integrityMismatchDialog = null;
    private SharedPreferences sp = null;
    private Context mContext;
    private CountDownTimer countDownTimer;
    private RecordingMode currentMode = RecordingMode.MANUAL;
    /** Accumulated transcript for continuous mode; sent as the activity result on finish. */
    private final StringBuilder continuousTranscript = new StringBuilder();

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        String targetLang = getIntent().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        String langCode = sp.getString("language", "auto");
        Log.d("WhisperRecognition","default langCode " + langCode);

        if (targetLang != null) {
            Log.d("WhisperRecognition","StartListening in " + targetLang);
            langCode = targetLang.split("[-_]")[0].toLowerCase();  //support both de_DE and de-DE
        } else {
            Log.d("WhisperRecognition","StartListening, no language specified");
        }

        initModel(langCode);

        setContentView(R.layout.activity_recognize);

        // Set the window layout parameters
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height =  WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM; // Position at the bottom of the screen

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        btnModeAuto = findViewById(R.id.btnModeAuto);
        btnContinuous = findViewById(R.id.btnContinuous);
        processingBar = findViewById(R.id.processing_bar);
        tvStatus = findViewById(R.id.tv_status);

        boolean savedAuto = sp.getBoolean("imeModeAuto", false);
        boolean savedContinuous = sp.getBoolean("imeContinuous", false);
        if (savedAuto && savedContinuous) {
            currentMode = RecordingMode.CONTINUOUS;
        } else if (savedAuto) {
            currentMode = RecordingMode.AUTO;
        }
        updateModeButtons();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_LISTENING)) {
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
                        tvStatus.setText(R.string.listening);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                } else if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_recording);
                        tvStatus.setText(R.string.recording);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_processing);
                        tvStatus.setText(R.string.processing);
                    });
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer != null) { countDownTimer.cancel(); }
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        processingBar.setProgress(0);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                }
            }

            @Override
            public void onUtteranceReady() {
                // Continuous mode: utterance handed off, start transcription
                if (currentMode == RecordingMode.CONTINUOUS) {
                    startTranscription();
                    runOnUiThread(() -> {
                        // Stay red — mic is still open for next utterance
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_recording);
                        tvStatus.setText(R.string.processing);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                }
            }
        });

        if (currentMode == RecordingMode.AUTO || currentMode == RecordingMode.CONTINUOUS) {
            // Auto/Continuous mode: starts recording immediately
            HapticFeedback.vibrate(this);
            startRecording();
            runOnUiThread(() -> {
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
                processingBar.setProgress(100);
                tvStatus.setText(R.string.listening);
                tvStatus.setVisibility(View.VISIBLE);
            });
            countDownTimer = new CountDownTimer(30000, 1000) {
                @Override
                public void onTick(long l) {
                    runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                }
                @Override
                public void onFinish() {}
            };
            countDownTimer.start();
        }

        btnModeAuto.setOnClickListener(v -> {
            // A button cycles: MANUAL → AUTO → CONTINUOUS → MANUAL
            // WhisperRecognizeActivity is a one-shot RecognizerIntent overlay; it always
            // finishes after a single recording session.  These mode buttons persist the
            // preference for the *next* launch — they do not switch modes mid-session.
            // In-session mode cycling is handled by WhisperInputMethodService (the IME).
            switch (currentMode) {
                case MANUAL:
                    currentMode = RecordingMode.AUTO;
                    break;
                case AUTO:
                    currentMode = RecordingMode.CONTINUOUS;
                    break;
                case CONTINUOUS:
                    currentMode = RecordingMode.MANUAL;
                    break;
            }
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", currentMode != RecordingMode.MANUAL);
            editor.putBoolean("imeContinuous", currentMode == RecordingMode.CONTINUOUS);
            editor.apply();
            updateModeButtons();
            if (mWhisper != null) stopTranscription();
            // Switching modes mid-session commits any accumulated continuous transcript.
            // Intentional: the user was actively dictating; switching away is "done, keep text".
            // If nothing was spoken yet, cancel instead (no partial result to commit).
            // Note: the dedicated Cancel button always returns RESULT_CANCELED unconditionally.
            if (continuousTranscript.length() == 0) {
                setResult(RESULT_CANCELED, null);
            }
            finish();
        });

        btnContinuous.setOnClickListener(v -> {
            // ∞ button: MANUAL→CONTINUOUS, AUTO→CONTINUOUS, CONTINUOUS→AUTO
            if (currentMode == RecordingMode.CONTINUOUS) {
                currentMode = RecordingMode.AUTO;
            } else {
                // MANUAL or AUTO → CONTINUOUS
                currentMode = RecordingMode.CONTINUOUS;
            }
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", currentMode != RecordingMode.MANUAL);
            editor.putBoolean("imeContinuous", currentMode == RecordingMode.CONTINUOUS);
            editor.apply();
            updateModeButtons();
            if (mWhisper != null) stopTranscription();
            // Switching modes mid-session commits any accumulated continuous transcript.
            // Intentional: the user was actively dictating; switching away is "done, keep text".
            // If nothing was spoken yet, cancel instead (no partial result to commit).
            // Note: the dedicated Cancel button always returns RESULT_CANCELED unconditionally.
            if (continuousTranscript.length() == 0) {
                setResult(RESULT_CANCELED, null);
            }
            finish();
        });

        btnRecord.setOnTouchListener((v, event) -> {
            if (currentMode != RecordingMode.MANUAL) {
                // In auto/continuous mode, mic button is not used for press-and-hold
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                runOnUiThread(() -> {
                    btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
                    tvStatus.setText(R.string.listening);
                    tvStatus.setVisibility(View.VISIBLE);
                });
                if (checkRecordPermission()) {
                    if (!mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                        runOnUiThread(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(30000, 1000) {
                            @Override
                            public void onTick(long l) {
                                runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show());
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

        btnCancel.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

    }

    private void startRecording() {
        Recorder.VadMode vadMode;
        switch (currentMode) {
            case CONTINUOUS:
                vadMode = Recorder.VadMode.CONTINUOUS;
                break;
            case AUTO:
                vadMode = Recorder.VadMode.STOP_ON_SILENCE;
                break;
            default:
                vadMode = Recorder.VadMode.FILTER_SILENCE;
                break;
        }
        mRecorder.initVad(vadMode);
        mRecorder.start();
    }

    /** Updates A and ∞ button icons based on current mode. Both buttons are always visible. */
    private void updateModeButtons() {
        switch (currentMode) {
            case MANUAL:
                btnModeAuto.setImageResource(R.drawable.ic_auto_off_36dp);
                btnContinuous.setImageResource(R.drawable.ic_continuous_off_36dp);
                break;
            case AUTO:
                btnModeAuto.setImageResource(R.drawable.ic_auto_on_36dp);
                btnContinuous.setImageResource(R.drawable.ic_continuous_off_36dp);
                break;
            case CONTINUOUS:
                btnModeAuto.setImageResource(R.drawable.ic_auto_on_36dp);
                btnContinuous.setImageResource(R.drawable.ic_continuous_on_36dp);
                break;
        }
    }

    // Model initialization
    private void initModel(String langCode) {

        mWhisper = new Whisper(this);
        mWhisper.setLanguage(langCode);
        Log.d(TAG, "Language code " + langCode);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                runOnUiThread(() -> processingBar.setIndeterminate(false));

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                if (result.trim().length() > 0){
                    if (currentMode == RecordingMode.CONTINUOUS) {
                        // In continuous mode, accumulate result and keep listening
                        sendResultAndContinue(result.trim());
                    } else {
                        sendResult(result.trim());
                    }
                } else {
                    // Empty result in continuous mode — recorder still running, just update UI
                    if (currentMode == RecordingMode.CONTINUOUS) {
                        restartRecording();
                    }
                }
                // Drain any utterances that arrived in the queue while we were transcribing.
                if (currentMode == RecordingMode.CONTINUOUS && RecordBuffer.hasUtterances()) {
                    startTranscription();
                }
            }
        });
        if (mWhisper.isModelReady()) {
            new Thread(() -> {
                File modelDir = getExternalFilesDir(null);
                String modelName = ModelIntegrityChecker.getSelectedModel(WhisperRecognizeActivity.this);
                List<String> mismatches = ModelIntegrityChecker.getMismatches(modelDir, modelName);
                runOnUiThread(() -> {
                    if (mWhisper == null || isFinishing() || isDestroyed()) return;
                    if (mismatches.isEmpty()) {
                        mWhisper.loadModel();
                    } else {
                        String fileList = android.text.TextUtils.join(", ", mismatches);
                        integrityMismatchDialog = new AlertDialog.Builder(WhisperRecognizeActivity.this)
                                .setTitle("Model fingerprint mismatch")
                                .setMessage(
                                        "The model files do not match the known " +
                                        modelName + " fingerprint. This may indicate " +
                                        "file corruption or replacement." +
                                        "\n\nFile(s): " + fileList +
                                        "\n\nContinue using this model?")
                                .setPositiveButton("Continue", (d, w) -> { if (mWhisper != null) mWhisper.loadModel(); })
                                .setNegativeButton("Cancel", (d, w) -> finish())
                                .setCancelable(false)
                                .create();
                        if (!isFinishing() && !isDestroyed()) {
                            integrityMismatchDialog.show();
                        }
                    }
                });
            }, "integrity-check").start();
        }
    }

    private void sendResult(String result) {
        Intent sendResultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        sendResultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        sendResultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, sendResultIntent);
        finish();
    }

    /** In continuous mode: accumulate result and keep listening. */
    private void sendResultAndContinue(String result) {
        continuousTranscript.append(result).append(" ");
        // Keep the activity result up-to-date so the caller receives the full transcript
        // when this activity finishes (e.g. user taps ∞ or back to stop dictating).
        Intent pendingResult = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(continuousTranscript.toString().trim());
        pendingResult.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        pendingResult.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, pendingResult);
        // Recorder is still running in continuous mode — do not reinitialise or restart it.
        runOnUiThread(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_recording);
            tvStatus.setText(R.string.listening);
            tvStatus.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Updates the UI to the listening state and resets the countdown timer.
     * Only reinitialises VAD and calls start() if the recorder has actually stopped;
     * calling initVad() on an active recorder leaks the old VAD instance.
     */
    private void restartRecording() {
        runOnUiThread(() -> {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_listening);
            tvStatus.setText(R.string.listening);
            tvStatus.setVisibility(View.VISIBLE);
            processingBar.setProgress(100);
        });
        if (mRecorder != null && !mRecorder.isInProgress()) {
            mRecorder.initVad(Recorder.VadMode.CONTINUOUS);
            mRecorder.start();
        }
        if (countDownTimer != null) { countDownTimer.cancel(); }
        countDownTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long l) {
                runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
            }
            @Override
            public void onFinish() {}
        };
        countDownTimer.start();
    }

    private void startTranscription() {
        if (countDownTimer != null) { countDownTimer.cancel(); }
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        if (mWhisper != null) {
            mWhisper.setAction(ACTION_TRANSCRIBE);
            mWhisper.start();
            Log.d(TAG, "Start Transcription");
        }
    }

    private void stopTranscription() {
        runOnUiThread(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    @Override
    public void onDestroy() {
        if (integrityMismatchDialog != null && integrityMismatchDialog.isShowing()) {
            integrityMismatchDialog.dismiss();
        }
        deinitModel();
        if (mRecorder != null) {
            if (mRecorder.isInProgress()) {
                mRecorder.stop();
            }
            mRecorder.destroy();
        }
        super.onDestroy();
    }
}
