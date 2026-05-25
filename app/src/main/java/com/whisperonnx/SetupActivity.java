package com.whisperonnx;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.whisperonnx.utils.ModelIntegrityChecker;
import com.whisperonnx.utils.ThemeUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SetupActivity extends AppCompatActivity {
    private static final String TAG = "SetupActivity";
    ActivityResultLauncher<Intent> install;
    ProgressBar progressBar;
    TextView extractedFileTV;
    Button startButton;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        progressBar = findViewById(R.id.progress_bar);
        extractedFileTV = findViewById(R.id.extracted_file);
        startButton = findViewById(R.id.button_start);

        File sdcardDataFolder = getExternalFilesDir(null);

        install = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getData()!=null && result.getData().getData()!=null) zipExtract(this, sdcardDataFolder, result.getData().getData());
                });

    }

     public void downloadModel(View v){
         Toast.makeText(this,"Download",Toast.LENGTH_SHORT).show();
         startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/DocWolle/whisperOnnx/blob/main/whisper_small_int8.zip")));
     }
    public void installModel(View v){
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");
        install.launch(intent);

    }

    public void startMain(View v){
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public void zipExtract(Context context, File targetDir, Uri zipFile) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        Thread thread = new Thread(() -> {
            ZipEntry zipEntry;
            int readLen;
            byte[] readBuffer = new byte[4096];
            try {
                InputStream src = context.getContentResolver().openInputStream(zipFile);
                try {
                    try (ZipInputStream zipInputStream = new ZipInputStream(src)) {
                        String canonicalTarget = targetDir.getCanonicalPath() + File.separator;
                        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                            // Skip directory entries
                            if (zipEntry.isDirectory()) continue;

                            String entryName = zipEntry.getName();

                            // Reject absolute paths and Windows-style separators before
                            // canonical-path resolution, which normalises them in ways
                            // that could silently land inside targetDir.
                            if (entryName.startsWith("/") || entryName.startsWith("\\")
                                    || entryName.contains("\\")) {
                                Log.e(TAG, "Blocked unsafe zip entry (bad name): " + entryName);
                                continue;
                            }

                            File extractedFile = new File(targetDir, entryName);

                            // Zip Slip protection: verify entry stays within targetDir
                            if (!extractedFile.getCanonicalPath().startsWith(canonicalTarget)) {
                                Log.e(TAG, "Blocked unsafe zip entry: " + entryName);
                                continue;
                            }

                            // Ensure any intermediate directories exist
                            File parentDir = extractedFile.getParentFile();
                            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                                Log.e(TAG, "Failed to create directory: " + parentDir);
                                continue;
                            }

                            runOnUiThread(()->{
                                extractedFileTV.setVisibility(View.VISIBLE);
                                extractedFileTV.setText(extractedFile.getName());
                            });
                            try (OutputStream outputStream = Files.newOutputStream(extractedFile.toPath())) {
                                while ((readLen = zipInputStream.read(readBuffer)) != -1) {
                                    outputStream.write(readBuffer, 0, readLen);
                                }
                            }
                        }
                        // Hide progress indicators (always, regardless of hash result)
                        runOnUiThread(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setVisibility(View.GONE);
                            extractedFileTV.setVisibility(View.GONE);
                        });

                        // Verify SHA-256 hashes — still on background thread
                        List<String> mismatches = ModelIntegrityChecker.getMismatches(targetDir);
                        if (mismatches.isEmpty()) {
                            runOnUiThread(() -> startButton.setVisibility(View.VISIBLE));
                        } else {
                            String fileList = android.text.TextUtils.join(", ", mismatches);
                            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Model fingerprint mismatch")
                                    .setMessage(
                                            "The extracted files do not match the known " +
                                            "whisper-small-int8 fingerprint. This is expected " +
                                            "if you installed a different model variant, but " +
                                            "could also indicate a corrupted or modified file." +
                                            "\n\nFile(s): " + fileList +
                                            "\n\nContinue using this model?")
                                    .setPositiveButton("Continue",
                                            (d, w) -> startButton.setVisibility(View.VISIBLE))
                                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                                    .setCancelable(false)
                                    .show());
                        }
                    }
                } catch (IOException ioException) {
                    Log.e(TAG, "Error extracting zip", ioException);
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Zip file not found", e);
            }
        });
        thread.start();
    }
}
