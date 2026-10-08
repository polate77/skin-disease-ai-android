package com.mohammed.skindiseaseai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // UI elements
    private ImageView imageView;
    private View imagePlaceholder;
    private TextView resultText;
    private TextView resultDetails;
    private ProgressBar progressBar;
    private MaterialButton btnTakePhoto;
    private MaterialButton btnPickImage;
    private View resultIndicatorBadge;
    private TextView resultStatusText;

    // AI model
    private Interpreter tflite;
    private List<String> labels = new ArrayList<>();
    private static final int IMG_SIZE = 128;

    // Image capture/pick launchers
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private Uri cameraImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Link UI elements
        imageView = findViewById(R.id.imageView);
        imagePlaceholder = findViewById(R.id.imagePlaceholder);
        resultText = findViewById(R.id.resultText);
        resultDetails = findViewById(R.id.resultDetails);
        progressBar = findViewById(R.id.progressBar);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        btnPickImage = findViewById(R.id.btnPickImage);
        resultIndicatorBadge = findViewById(R.id.resultIndicatorBadge);
        resultStatusText = findViewById(R.id.resultStatusText);

        // Load AI model + labels
        try {
            tflite = new Interpreter(loadModelFile());
            labels = loadLabels();
            Toast.makeText(this, "AI model loaded ✅", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error loading model: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }

        setupLaunchers();
        setupButtons();
    }

    private void setupLaunchers() {
        // Camera launcher
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraImageUri != null) {
                        try {
                            Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                                    getContentResolver(), cameraImageUri);
                            showImage(bitmap);
                            classifyImage(bitmap);
                        } catch (IOException e) {
                            Toast.makeText(this, "Error reading photo", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        // Gallery launcher
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        try {
                            InputStream is = getContentResolver().openInputStream(uri);
                            Bitmap bitmap = BitmapFactory.decodeStream(is);
                            showImage(bitmap);
                            classifyImage(bitmap);
                        } catch (IOException e) {
                            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        // Camera permission launcher
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        launchCamera();
                    } else {
                        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setupButtons() {
        btnTakePhoto.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnPickImage.setOnClickListener(v -> galleryLauncher.launch("image/*"));
    }

    private void launchCamera() {
        try {
            java.io.File photoFile = java.io.File.createTempFile(
                    "skin_photo", ".jpg", getCacheDir());
            cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (IOException e) {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showImage(Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        if (imagePlaceholder != null) {
            imagePlaceholder.setVisibility(View.GONE);
        }
    }

    private void classifyImage(Bitmap bitmap) {
        if (tflite == null) {
            resultText.setText("Model not loaded");
            return;
        }

        // Resize to 128x128
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true);

        // Convert to ByteBuffer (float32, normalized to 0-1)
        ByteBuffer input = ByteBuffer.allocateDirect(4 * IMG_SIZE * IMG_SIZE * 3);
        input.order(ByteOrder.nativeOrder());
        int[] pixels = new int[IMG_SIZE * IMG_SIZE];
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE);
        for (int pixel : pixels) {
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            input.putFloat(r);
            input.putFloat(g);
            input.putFloat(b);
        }
        input.rewind();

        // Output array (7 classes)
        float[][] output = new float[1][labels.size()];

        // Run inference
        tflite.run(input, output);

        // Show top prediction
        int topIdx = 0;
        float topProb = output[0][0];
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > topProb) {
                topProb = output[0][i];
                topIdx = i;
            }
        }

        String topLabel = labels.get(topIdx);
        int percent = Math.round(topProb * 100);

        resultText.setText(String.format("%s  %d%%", topLabel.toUpperCase(), percent));
        resultDetails.setText(buildDetailsText(output[0]));

        // Update indicator color based on prediction class
        updateResultIndicator(topLabel);
    }

    private void updateResultIndicator(String label) {
        if (resultIndicatorBadge == null || resultStatusText == null) return;

        int color;
        String status;

        switch (label.toLowerCase()) {
            case "nv":
                color = ContextCompat.getColor(this, R.color.risk_green);
                status = "BENIGN (NV)";
                break;
            case "bkl":
                color = ContextCompat.getColor(this, R.color.risk_yellow);
                status = "BENIGN (BKL)";
                break;
            case "mel":
                color = ContextCompat.getColor(this, R.color.risk_red);
                status = "MELANOMA (MEL)";
                break;
            case "df":
            case "vasc":
                color = ContextCompat.getColor(this, R.color.risk_green);
                status = "BENIGN (" + label.toUpperCase() + ")";
                break;
            case "akiec":
                color = ContextCompat.getColor(this, R.color.risk_yellow);
                status = "PRE-CANCER (" + label.toUpperCase() + ")";
                break;
            case "bcc":
                color = ContextCompat.getColor(this, R.color.risk_red);
                status = "CARCINOMA (BCC)";
                break;
            default:
                color = ContextCompat.getColor(this, R.color.medical_secondary);
                status = label.toUpperCase();
                break;
        }

        if (resultIndicatorBadge.getBackground() != null) {
            resultIndicatorBadge.getBackground().setTint(color);
        }
        resultStatusText.setText(status);
    }

    private String buildDetailsText(float[] probs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < probs.length; i++) {
            sb.append(labels.get(i))
                    .append(": ")
                    .append(Math.round(probs[i] * 100))
                    .append("%\n");
        }
        return sb.toString().trim();
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        android.content.res.AssetFileDescriptor fd = getAssets().openFd("skin_model.tflite");
        java.io.FileInputStream is = new java.io.FileInputStream(fd.getFileDescriptor());
        FileChannel fileChannel = is.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(), fd.getDeclaredLength());
    }

    private List<String> loadLabels() throws IOException {
        List<String> result = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open("labels.txt")));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) result.add(line.trim());
        }
        reader.close();
        return result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) tflite.close();
    }
}