package com.example.sopranorecorderflashcards;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 101;
    private static final int SAMPLE_RATE = 44100;

    private PermissionRequest pendingAudioRequest;
    private Runnable pendingNativeMicStart;
    private WebView webView;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new NativeMicBridge(), "NativeMic");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED) {
                                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                            } else {
                                pendingAudioRequest = request;
                                ActivityCompat.requestPermissions(
                                        MainActivity.this,
                                        new String[]{Manifest.permission.RECORD_AUDIO},
                                        REQUEST_RECORD_AUDIO
                                );
                            }
                            return;
                        }
                    }
                    request.deny();
                });
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startNativeRecordingInternal() {
        stopNativeRecordingInternal();

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuf, 2048 * 2);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                notifyJsError("AudioRecordInitError");
                return;
            }

            isRecording = true;
            audioRecord.startRecording();

            recordingThread = new Thread(() -> {
                short[] pcmBuffer = new short[2048];
                while (isRecording && audioRecord != null) {
                    int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                    if (read > 0 && isRecording) {
                        StringBuilder csv = new StringBuilder(read * 6);
                        for (int i = 0; i < read; i++) {
                            if (i > 0) csv.append(',');
                            float sample = pcmBuffer[i] / 32768.0f;
                            csv.append(Math.round(sample * 10000.0f) / 10000.0f);
                        }
                        final String payload = csv.toString();
                        mainHandler.post(() -> {
                            if (isRecording && webView != null) {
                                String js = "window.onNativeAudioSamples && window.onNativeAudioSamples(["
                                        + payload + "], " + SAMPLE_RATE + ");";
                                webView.evaluateJavascript(js, null);
                            }
                        });
                    }
                }
            }, "RecorderNativeMicThread");

            recordingThread.start();
            mainHandler.post(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("window.onNativeMicStarted && window.onNativeMicStarted();", null);
                }
            });
        } catch (SecurityException se) {
            notifyJsError("SecurityException");
        } catch (Exception e) {
            notifyJsError(e.getClass().getSimpleName());
        }
    }

    private void stopNativeRecordingInternal() {
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }

    private void notifyJsError(String code) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.onNativeMicError && window.onNativeMicError('" + code + "');",
                        null
                );
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (pendingAudioRequest != null) {
                if (granted) {
                    pendingAudioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingAudioRequest.deny();
                }
                pendingAudioRequest = null;
            }

            Runnable cb = pendingNativeMicStart;
            pendingNativeMicStart = null;
            if (granted && cb != null) {
                cb.run();
            } else if (!granted && cb != null) {
                notifyJsError("PermissionDenied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopNativeRecordingInternal();
        super.onDestroy();
    }

    private class NativeMicBridge {
        @JavascriptInterface
        public void startMic() {
            mainHandler.post(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    startNativeRecordingInternal();
                } else {
                    pendingNativeMicStart = MainActivity.this::startNativeRecordingInternal;
                    ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_RECORD_AUDIO
                    );
                }
            });
        }

        @JavascriptInterface
        public void stopMic() {
            mainHandler.post(() -> stopNativeRecordingInternal());
        }
    }
}