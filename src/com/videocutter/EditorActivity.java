package com.videocutter;

import android.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.graphics.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class EditorActivity extends Activity {

    private static final int REQ_STORAGE = 201;
    private static final int NUM_THUMBNAILS = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();

    // views
    private TextureView textureView;
    private CropOverlayView cropOverlay;
    private FrameLayout playerContainer;
    private ImageButton buttonPlayPause;
    private ImageButton buttonCrop;
    private TextView textStartTime;
    private TextView textEndTime;
    private TextView textTotalDuration;
    private TextView textCursorLabel;
    private EditText editTitleBar;
    private View viewCursor;
    private View viewSelectionBorder;
    private View layoutTimelineContainer;
    private LinearLayout layoutThumbnails;
    private RangeSelectorView rangeSelector;
    private View layoutTimePicker;
    private View layoutLoadingOverlay;
    private ProgressBar progressExport;
    private TextView textExportStatus;
    private TextView textPickerTitle;
    private NumberPicker pickerMin;
    private NumberPicker pickerSec;
    private NumberPicker pickerMs;

    // player
    private MediaPlayer player;
    private boolean playerPrepared = false;
    private Surface surface;
    private boolean surfaceReady = false;
    private boolean playing = false;

    // state
    private VideoFile videoFile;
    private long startTimeMs = 0L;
    private long endTimeMs = 0L;
    private long durationMs = 0L;
    private boolean isPickingStartTime = true;
    private boolean exporting = false;

    /** About one frame at 30 fps, refined from metadata when available. */
    private long frameDurationMs = 33L;
    private int videoWidthPx = 0;
    private int videoHeightPx = 0;

    private SharedPreferences trimPrefs;

    private final Runnable updateCursorRunnable = new Runnable() {
        @Override
        public void run() {
            updateCursorPosition();
            checkPlaybackLoop();
            handler.postDelayed(this, 30);
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.editor_activity);
        trimPrefs = getSharedPreferences("video_cutter_trim_ranges", MODE_PRIVATE);

        findViews();

        // Handle VIEW / SEND intents from other apps
        Intent intent = getIntent();
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action)) {
            Uri uri;
            if (Intent.ACTION_SEND.equals(action)) {
                uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            } else {
                uri = intent.getData();
            }
            if (uri != null) videoFile = loadMediaFileFromUri(uri);
        } else {
            videoFile = (VideoFile) intent.getParcelableExtra("EXTRA_MEDIA_FILE");
        }

        if (videoFile == null) {
            Toast.makeText(this, "Could not load file", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // If the duration was missing from the list item, resolve it now
        if (videoFile.duration <= 0L) {
            VideoFile resolved = loadMediaFileFromUri(videoFile.uri);
            if (resolved != null && resolved.duration > 0L) {
                videoFile = videoFile.withDurationAndResolution(resolved.duration,
                        resolved.resolution != null ? resolved.resolution : videoFile.resolution);
            }
        }

        detectFrameDuration();
        setupTitleBar();
        setupPlayerSurface();
        setupTimeline();
        setupButtons();
        setupTimePicker();
    }

    private void findViews() {
        editTitleBar = (EditText) findViewById(R.id.edit_title_bar);
        textureView = (TextureView) findViewById(R.id.texture_view);
        cropOverlay = (CropOverlayView) findViewById(R.id.crop_overlay);
        playerContainer = (FrameLayout) findViewById(R.id.layout_player_container);
        buttonPlayPause = (ImageButton) findViewById(R.id.button_play_pause);
        buttonCrop = (ImageButton) findViewById(R.id.button_crop);
        textStartTime = (TextView) findViewById(R.id.text_start_time);
        textEndTime = (TextView) findViewById(R.id.text_end_time);
        textTotalDuration = (TextView) findViewById(R.id.text_total_duration);
        textCursorLabel = (TextView) findViewById(R.id.text_cursor_label);
        viewCursor = findViewById(R.id.view_cursor);
        viewSelectionBorder = findViewById(R.id.view_selection_border);
        layoutTimelineContainer = findViewById(R.id.layout_timeline_container);
        layoutThumbnails = (LinearLayout) findViewById(R.id.layout_thumbnails);
        rangeSelector = (RangeSelectorView) findViewById(R.id.range_selector);
        layoutTimePicker = findViewById(R.id.layout_time_picker);
        layoutLoadingOverlay = findViewById(R.id.layout_loading_overlay);
        progressExport = (ProgressBar) findViewById(R.id.progress_export);
        textExportStatus = (TextView) findViewById(R.id.text_export_status);
        textPickerTitle = (TextView) findViewById(R.id.text_picker_title);
        pickerMin = (NumberPicker) findViewById(R.id.picker_min);
        pickerSec = (NumberPicker) findViewById(R.id.picker_sec);
        pickerMs = (NumberPicker) findViewById(R.id.picker_ms);
    }

    // ------------------------------------------------------------------
    // Editable title bar
    // ------------------------------------------------------------------

    private void setupTitleBar() {
        editTitleBar.setText(videoFile.title);
        editTitleBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                commitTitle();
                editTitleBar.clearFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(editTitleBar.getWindowToken(), 0);
                return true;
            }
        });
        editTitleBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) commitTitle();
            }
        });
    }

    /** Stores the edited name in the app so the list and the exported file name use it. */
    private void commitTitle() {
        if (videoFile == null || editTitleBar == null) return;
        String t = editTitleBar.getText().toString().trim();
        if (t.length() == 0) {
            editTitleBar.setText(videoFile.title);
            return;
        }
        if (t.equals(videoFile.title)) return;
        videoFile = videoFile.withTitle(t);
        PlaylistRepository.saveCustomTitle(getApplicationContext(), videoFile.uri, t);
        PlaylistRepository.updateFile(videoFile);
        Toast.makeText(this, "Name saved", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        commitTitle();
        saveTrimRange();
        if (playing) pausePlayback();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveTrimRange();
        super.onDestroy();
        handler.removeCallbacks(updateCursorRunnable);
        releasePlayer();
        exportExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (exporting) return;
        if (layoutTimePicker.getVisibility() == View.VISIBLE) {
            layoutTimePicker.setVisibility(View.GONE);
            seekTo(isPickingStartTime ? startTimeMs : endTimeMs);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Saved trim range
    // ------------------------------------------------------------------

    private String trimKeyPrefix() {
        if (videoFile == null || videoFile.uri == null) return null;
        String s = videoFile.uri.toString();
        return s.length() == 0 ? null : s;
    }

    private static long clampLong(long v, long lo, long hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private long[] loadSavedTrimRange(long duration) {
        String prefix = trimKeyPrefix();
        if (prefix == null) return new long[]{0L, duration};
        long start = trimPrefs.getLong(prefix + "_start", 0L);
        long end = trimPrefs.getLong(prefix + "_end", duration);
        long safeStart = clampLong(start, 0L, duration);
        long safeEnd = clampLong(end, 0L, duration);
        if (safeStart < safeEnd) return new long[]{safeStart, safeEnd};
        return new long[]{0L, duration};
    }

    private void saveTrimRange() {
        String prefix = trimKeyPrefix();
        if (prefix == null || durationMs <= 0L) return;
        trimPrefs.edit()
                .putLong(prefix + "_start", clampLong(startTimeMs, 0L, durationMs))
                .putLong(prefix + "_end", clampLong(endTimeMs, 0L, durationMs))
                .apply();
    }

    // ------------------------------------------------------------------
    // Player (MediaPlayer + TextureView)
    // ------------------------------------------------------------------

    private void setupPlayerSurface() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surface = new Surface(st);
                surfaceReady = true;
                setupPlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) surface.release();
                surface = null;
                surfaceReady = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void setupPlayer() {
        if (!surfaceReady || player != null || videoFile == null) return;
        Uri uri = getIntent().getData() != null ? getIntent().getData() : videoFile.uri;
        try {
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setDataSource(this, uri);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    playerPrepared = true;
                    if (durationMs <= 0L && mp.getDuration() > 0) {
                        durationMs = mp.getDuration();
                        setupTimeline();
                        setupTimePicker();
                    }
                    if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                        videoWidthPx = mp.getVideoWidth();
                        videoHeightPx = mp.getVideoHeight();
                    }
                    updateVideoLayout();
                    seekTo(startTimeMs);
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (playing) pausePlayback();
                    seekTo(startTimeMs);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Toast.makeText(EditorActivity.this, "Playback error", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
            releasePlayer();
            Toast.makeText(this, "Could not open video for preview", Toast.LENGTH_SHORT).show();
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(updateCursorRunnable);
        playing = false;
        playerPrepared = false;
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void startPlayback() {
        if (player == null || !playerPrepared) return;
        try {
            player.start();
        } catch (Exception e) {
            return;
        }
        playing = true;
        buttonPlayPause.setImageResource(R.drawable.pause_24px);
        textCursorLabel.setVisibility(View.VISIBLE);
        handler.post(updateCursorRunnable);
    }

    private void pausePlayback() {
        if (player != null && playerPrepared) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
        }
        playing = false;
        buttonPlayPause.setImageResource(R.drawable.play_arrow_24px);
        handler.removeCallbacks(updateCursorRunnable);
    }

    private void seekTo(long positionMs) {
        if (player != null && playerPrepared) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    player.seekTo((int) positionMs, MediaPlayer.SEEK_CLOSEST);
                } else {
                    player.seekTo((int) positionMs);
                }
            } catch (Exception ignored) {
            }
        }
        updateCursorPosition(positionMs);
    }

    private long currentPosition() {
        if (player != null && playerPrepared) {
            try {
                return player.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private void checkPlaybackLoop() {
        long pos = currentPosition();
        if (pos >= endTimeMs || pos < startTimeMs) {
            seekTo(startTimeMs);
            if (playing) pausePlayback();
        }
    }

    // ------------------------------------------------------------------
    // Timeline, thumbnails and labels
    // ------------------------------------------------------------------

    private void setupTimeline() {
        if (durationMs <= 0L) durationMs = videoFile.duration;

        long[] saved = loadSavedTrimRange(durationMs);
        startTimeMs = saved[0];
        endTimeMs = saved[1];

        rangeSelector.setDuration(Math.max(1L, durationMs));
        rangeSelector.setValues(startTimeMs, endTimeMs);
        rangeSelector.setListener(new RangeSelectorView.Listener() {
            @Override
            public void onRangeChanged(long newStart, long newEnd, boolean fromUser) {
                if (newStart != startTimeMs) {
                    startTimeMs = newStart;
                    seekTo(startTimeMs);
                } else if (newEnd != endTimeMs) {
                    endTimeMs = newEnd;
                    seekTo(endTimeMs);
                }
                updateTrimLabels();
                if (fromUser) saveTrimRange();
            }

            @Override
            public void onScrub(long positionMs, boolean scrubbing) {
                if (scrubbing) {
                    seekTo(positionMs);
                    textCursorLabel.setVisibility(View.VISIBLE);
                    textCursorLabel.setText(formatTimeDecimal(positionMs));
                    positionCursorLabel();
                } else if (!playing) {
                    textCursorLabel.setVisibility(View.GONE);
                }
            }
        });

        updateTrimLabels();

        if (videoFile.isVideo) buildThumbnails();

        layoutTimelineContainer.post(new Runnable() {
            @Override
            public void run() {
                updateSelectionBorder();
                if (startTimeMs > 0L) seekTo(startTimeMs);
            }
        });
    }

    private void buildThumbnails() {
        layoutThumbnails.removeAllViews();
        final ImageView[] views = new ImageView[NUM_THUMBNAILS];
        for (int i = 0; i < NUM_THUMBNAILS; i++) {
            ImageView img = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            img.setLayoutParams(lp);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            layoutThumbnails.addView(img);
            views[i] = img;
        }
        final Uri uri = videoFile.uri;
        final long interval = Math.max(1L, durationMs / NUM_THUMBNAILS);
        thumbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(EditorActivity.this, uri);
                    for (int i = 0; i < NUM_THUMBNAILS; i++) {
                        Bitmap full = r.getFrameAtTime(i * interval * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (full == null) continue;
                        int h = 128;
                        int w = Math.max(1, full.getWidth() * h / Math.max(1, full.getHeight()));
                        final Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
                        if (small != full) full.recycle();
                        final ImageView target = views[i];
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                target.setImageBitmap(small);
                            }
                        });
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void updateSelectionBorder() {
        int containerWidth = layoutThumbnails.getWidth();
        if (durationMs > 0L && containerWidth > 0) {
            float startX = (float) startTimeMs / (float) durationMs * containerWidth;
            float endX = (float) endTimeMs / (float) durationMs * containerWidth;
            ViewGroup.LayoutParams params = viewSelectionBorder.getLayoutParams();
            params.width = (int) (endX - startX);
            viewSelectionBorder.setLayoutParams(params);
            viewSelectionBorder.setTranslationX(startX);
        }
    }

    private SpannableString underlined(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new UnderlineSpan(), 0, s.length(), 0);
        return s;
    }

    private void updateTrimLabels() {
        textStartTime.setText(underlined(formatTimeDecimal(startTimeMs)));
        textEndTime.setText(underlined(formatTimeDecimal(endTimeMs)));
        textTotalDuration.setText("Total " + formatTimeDecimal(endTimeMs - startTimeMs));
        updateSelectionBorder();
    }

    private void updateCursorPosition() {
        updateCursorPosition(currentPosition());
    }

    private void updateCursorPosition(long currentPos) {
        int timelineWidth = layoutThumbnails.getWidth();
        if (durationMs > 0L && timelineWidth > 0) {
            float translationX = (float) currentPos / (float) durationMs * timelineWidth;
            viewCursor.setTranslationX(translationX);
            textCursorLabel.setText(formatTimeDecimal(currentPos));
            positionCursorLabel();
        }
    }

    private void positionCursorLabel() {
        if (textCursorLabel.getWidth() == 0) {
            textCursorLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        }
        int labelWidth = textCursorLabel.getWidth() > 0
                ? textCursorLabel.getWidth() : textCursorLabel.getMeasuredWidth();
        int containerWidth = layoutTimelineContainer.getWidth();
        float maxTrans = Math.max(0f, containerWidth - labelWidth);
        float x = viewCursor.getTranslationX() - labelWidth / 2f;
        textCursorLabel.setTranslationX(Math.max(0f, Math.min(maxTrans, x)));
    }

    private String formatTimeDecimal(long timeMs) {
        if (timeMs < 0L) timeMs = 0L;
        long totalSeconds = timeMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long decisecond = (timeMs % 1000L) / 100L;
        return String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, decisecond);
    }

    // ------------------------------------------------------------------
    // Buttons, nudging, reset, crop
    // ------------------------------------------------------------------

    private void setupButtons() {
        buttonPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playing) pausePlayback(); else startPlayback();
            }
        });
        findViewById(R.id.button_prev_frame).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekTo(startTimeMs);
            }
        });
        findViewById(R.id.button_save_check).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveMedia();
            }
        });
        findViewById(R.id.button_cancel_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.button_reset_trim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetTrimRange();
            }
        });
        textStartTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimePicker(true);
            }
        });
        textEndTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimePicker(false);
            }
        });

        bindNudge(R.id.button_start_minus_frame, true, -1, true);
        bindNudge(R.id.button_start_plus_frame, true, 1, true);
        bindNudge(R.id.button_start_minus_tenth, true, -1, false);
        bindNudge(R.id.button_start_plus_tenth, true, 1, false);
        bindNudge(R.id.button_end_minus_frame, false, -1, true);
        bindNudge(R.id.button_end_plus_frame, false, 1, true);
        bindNudge(R.id.button_end_minus_tenth, false, -1, false);
        bindNudge(R.id.button_end_plus_tenth, false, 1, false);

        buttonCrop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCropOverlay();
            }
        });
        buttonCrop.setAlpha(0.55f);

        playerContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) updateVideoLayout();
            }
        });
        playerContainer.post(new Runnable() {
            @Override
            public void run() {
                updateVideoLayout();
            }
        });
    }

    private void bindNudge(int id, final boolean isStart, final int sign, final boolean frame) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long delta = sign * (frame ? frameDurationMs : 100L);
                if (isStart) nudgeStart(delta); else nudgeEnd(delta);
            }
        });
    }

    private void nudgeStart(long deltaMs) {
        long newStart = clampLong(startTimeMs + deltaMs, 0L,
                Math.max(0L, endTimeMs - frameDurationMs));
        if (newStart == startTimeMs) return;
        startTimeMs = newStart;
        rangeSelector.setValues(startTimeMs, endTimeMs);
        updateTrimLabels();
        seekTo(startTimeMs);
        saveTrimRange();
    }

    private void nudgeEnd(long deltaMs) {
        long minEnd = Math.min(startTimeMs + frameDurationMs, durationMs);
        long newEnd = clampLong(endTimeMs + deltaMs, minEnd, durationMs);
        if (newEnd == endTimeMs) return;
        endTimeMs = newEnd;
        rangeSelector.setValues(startTimeMs, endTimeMs);
        updateTrimLabels();
        seekTo(endTimeMs);
        saveTrimRange();
    }

    /** Reset trim handles to the full video length and clear the saved range. */
    private void resetTrimRange() {
        if (durationMs <= 0L) return;
        startTimeMs = 0L;
        endTimeMs = durationMs;
        rangeSelector.setValues(0L, durationMs);
        updateTrimLabels();
        seekTo(0L);

        String prefix = trimKeyPrefix();
        if (prefix != null) {
            trimPrefs.edit().remove(prefix + "_start").remove(prefix + "_end").apply();
        }
        Toast.makeText(this, "Trim reset to full video", Toast.LENGTH_SHORT).show();
    }

    private void detectFrameDuration() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, videoFile.uri);
            String fpsStr = null;
            if (Build.VERSION.SDK_INT >= 23) {
                fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            }
            String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (fpsStr != null) {
                float fps = Float.parseFloat(fpsStr);
                if (fps > 1f && fps < 240f) {
                    frameDurationMs = Math.max(1L, (long) (1000f / fps));
                }
            }
            int w = wStr != null ? Integer.parseInt(wStr) : 0;
            int h = hStr != null ? Integer.parseInt(hStr) : 0;
            int rot = rotStr != null ? Integer.parseInt(rotStr) : 0;
            if (rot == 90 || rot == 270) {
                videoWidthPx = h;
                videoHeightPx = w;
            } else {
                videoWidthPx = w;
                videoHeightPx = h;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void toggleCropOverlay() {
        boolean enable = !cropOverlay.isCropEnabled();
        updateVideoLayout();
        cropOverlay.setCropEnabled(enable);
        buttonCrop.setAlpha(enable ? 1f : 0.55f);
        Toast.makeText(this, enable ? "Drag the rectangle to crop" : "Crop off",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Fits the video into the player container (letterbox) by sizing the TextureView, then
     * tells the crop overlay which area maps to the actual video frame.
     */
    private void updateVideoLayout() {
        int cw = playerContainer.getWidth();
        int ch = playerContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;

        int vw = videoWidthPx;
        int vh = videoHeightPx;
        int targetW;
        int targetH;
        if (vw <= 0 || vh <= 0) {
            targetW = cw;
            targetH = ch;
        } else {
            float videoAspect = (float) vw / (float) vh;
            float viewAspect = (float) cw / (float) ch;
            if (viewAspect > videoAspect) {
                targetH = ch;
                targetW = Math.round(ch * videoAspect);
            } else {
                targetW = cw;
                targetH = Math.round(cw / videoAspect);
            }
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW;
            lp.height = targetH;
            lp.gravity = Gravity.CENTER;
            textureView.setLayoutParams(lp);
        }

        float left = (cw - targetW) / 2f;
        float top = (ch - targetH) / 2f;
        cropOverlay.setContentBounds(left, top, left + targetW, top + targetH);
    }

    // ------------------------------------------------------------------
    // Time picker
    // ------------------------------------------------------------------

    private void setupTimePicker() {
        final long maxTotalSeconds = durationMs / 1000L;
        final int maxMin = (int) (maxTotalSeconds / 60L);
        final int maxSecRemain = (int) (maxTotalSeconds % 60L);
        final int maxMsRemain = (int) ((durationMs % 1000L) / 100L);

        NumberPicker.Formatter twoDigits = new NumberPicker.Formatter() {
            @Override
            public String format(int value) {
                return String.format(Locale.US, "%02d", value);
            }
        };

        pickerMin.setMinValue(0);
        pickerMin.setMaxValue(maxMin);
        pickerMin.setWrapSelectorWheel(false);
        pickerMin.setFormatter(twoDigits);

        pickerSec.setMinValue(0);
        pickerSec.setMaxValue(59);
        pickerSec.setWrapSelectorWheel(false);
        pickerSec.setFormatter(twoDigits);

        pickerMs.setMinValue(0);
        pickerMs.setMaxValue(9);
        pickerMs.setWrapSelectorWheel(false);

        NumberPicker.OnValueChangeListener changeListener = new NumberPicker.OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                // Limit the pickers to the video's duration
                if (pickerMin.getValue() == maxMin) {
                    pickerSec.setMaxValue(maxSecRemain);
                    if (pickerSec.getValue() > maxSecRemain) pickerSec.setValue(maxSecRemain);
                    if (pickerSec.getValue() == maxSecRemain) {
                        pickerMs.setMaxValue(maxMsRemain);
                        if (pickerMs.getValue() > maxMsRemain) pickerMs.setValue(maxMsRemain);
                    } else {
                        pickerMs.setMaxValue(9);
                    }
                } else {
                    pickerSec.setMaxValue(59);
                    pickerMs.setMaxValue(9);
                }
                // Preview while scrolling, without saving yet
                seekTo(Math.min(pickerTimeMs(), durationMs));
            }
        };
        pickerMin.setOnValueChangedListener(changeListener);
        pickerSec.setOnValueChangedListener(changeListener);
        pickerMs.setOnValueChangedListener(changeListener);

        findViewById(R.id.button_picker_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutTimePicker.setVisibility(View.GONE);
                seekTo(isPickingStartTime ? startTimeMs : endTimeMs);
            }
        });

        findViewById(R.id.button_picker_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long safeTimeMs = Math.min(pickerTimeMs(), durationMs);
                if (isPickingStartTime) {
                    if (safeTimeMs < endTimeMs) {
                        startTimeMs = safeTimeMs;
                    } else {
                        Toast.makeText(EditorActivity.this, "Start time must be before end time",
                                Toast.LENGTH_SHORT).show();
                        seekTo(startTimeMs);
                        return;
                    }
                } else {
                    if (safeTimeMs > startTimeMs) {
                        endTimeMs = safeTimeMs;
                    } else {
                        Toast.makeText(EditorActivity.this, "End time must be after start time",
                                Toast.LENGTH_SHORT).show();
                        seekTo(endTimeMs);
                        return;
                    }
                }
                rangeSelector.setValues(startTimeMs, endTimeMs);
                updateTrimLabels();
                saveTrimRange();
                seekTo(isPickingStartTime ? startTimeMs : endTimeMs);
                layoutTimePicker.setVisibility(View.GONE);
            }
        });
    }

    private long pickerTimeMs() {
        return pickerMin.getValue() * 60000L + pickerSec.getValue() * 1000L + pickerMs.getValue() * 100L;
    }

    private void showTimePicker(boolean isStart) {
        if (playing) pausePlayback();
        isPickingStartTime = isStart;
        layoutTimePicker.setVisibility(View.VISIBLE);
        textPickerTitle.setText(isStart ? "Set start time" : "Set end time");

        long timeMs = isStart ? startTimeMs : endTimeMs;
        long totalSecs = timeMs / 1000L;
        pickerMin.setValue((int) (totalSecs / 60L));
        pickerSec.setValue((int) (totalSecs % 60L));
        pickerMs.setValue((int) ((timeMs % 1000L) / 100L));
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private void saveMedia() {
        if (exporting || videoFile == null) return;
        commitTitle();

        // Before Android 10 saving to the Movies folder needs the storage permission
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }

        saveTrimRange();
        exporting = true;

        // Free the decoder and memory for the export
        releasePlayer();
        buttonPlayPause.setImageResource(R.drawable.play_arrow_24px);

        progressExport.setProgress(0);
        textExportStatus.setText("Exporting... 0%");
        layoutLoadingOverlay.setVisibility(View.VISIBLE);

        final Uri sourceUri = videoFile.uri;
        final long start = startTimeMs;
        final long end = endTimeMs;
        final float[] crop = cropOverlay.getCropFractions();
        final File temp = new File(getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir(),
                "temp_trim_" + System.currentTimeMillis() + ".mp4");

        exportExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Exception error = null;
                try {
                    VideoExporter.export(EditorActivity.this, sourceUri, temp, start, end, crop,
                            new VideoExporter.ProgressListener() {
                                @Override
                                public void onProgress(final int percent) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            progressExport.setProgress(percent);
                                            textExportStatus.setText("Exporting... " + percent + "%");
                                        }
                                    });
                                }
                            });
                } catch (Exception e) {
                    e.printStackTrace();
                    error = e;
                }
                final Exception failure = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onExportFinished(temp, failure);
                    }
                });
            }
        });
    }

    private void onExportFinished(File temp, Exception failure) {
        if (failure != null) {
            temp.delete();
            layoutLoadingOverlay.setVisibility(View.GONE);
            exporting = false;
            Toast.makeText(this, "Export Failed: " + failure.getMessage(), Toast.LENGTH_LONG).show();
            setupPlayer();
            return;
        }
        progressExport.setProgress(100);
        textExportStatus.setText("Saving...");
        Uri savedUri = saveToGallery(temp);
        layoutLoadingOverlay.setVisibility(View.GONE);
        exporting = false;
        setupPlayer();
        if (savedUri != null) offerShare(savedUri);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveMedia();
            } else {
                Toast.makeText(this, "Storage permission is needed to save the video",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Saves the trimmed file next to the source when possible. Returns the content URI. */
    private Uri saveToGallery(File tempFile) {
        try {
            Uri sourceUri = videoFile.uri;
            boolean isVideo = videoFile.isVideo;
            long timestamp = System.currentTimeMillis() / 1000L;

            String originalName = resolveOriginalDisplayName(sourceUri);
            int dot = originalName.lastIndexOf('.');
            String baseName = dot > 0 ? originalName.substring(0, dot) : originalName;
            String extension = isVideo ? "mp4" : "m4a";
            String mimeType = isVideo ? "video/mp4" : "audio/mp4";
            String displayName = baseName + "_trim." + extension;

            String relativePath = resolveSourceRelativePath(sourceUri);
            if (relativePath == null) {
                relativePath = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC;
            }

            Uri itemUri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.DATE_ADDED, timestamp);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put("relative_path", relativePath);
                values.put("is_pending", 1);
                Uri collection = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                itemUri = getContentResolver().insert(collection, values);
                if (itemUri == null) throw new Exception("Failed to create MediaStore entry");

                copyFileToUri(tempFile, itemUri);

                values.clear();
                values.put("is_pending", 0);
                getContentResolver().update(itemUri, values, null, null);
            } else {
                // Android 9 and older: write the file directly and let the media scanner index it
                File root = Environment.getExternalStorageDirectory();
                File dir = new File(root, relativePath);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, displayName);
                int n = 2;
                while (out.exists()) {
                    out = new File(dir, baseName + "_trim_" + n + "." + extension);
                    n++;
                }
                FileInputStream in = new FileInputStream(tempFile);
                FileOutputStream os = new FileOutputStream(out);
                copyStreams(in, os);
                displayName = out.getName();
                MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                        new String[]{mimeType}, null);
                itemUri = Uri.fromFile(out);
            }

            Toast.makeText(this, "Saved as " + displayName, Toast.LENGTH_LONG).show();
            tempFile.delete();
            return itemUri;
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void copyFileToUri(File file, Uri uri) throws Exception {
        OutputStream os = getContentResolver().openOutputStream(uri);
        if (os == null) throw new Exception("Could not open output stream");
        copyStreams(new FileInputStream(file), os);
    }

    private static void copyStreams(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void offerShare(final Uri savedUri) {
        new AlertDialog.Builder(this)
                .setTitle("Export complete")
                .setMessage("Share the trimmed video now?")
                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType(videoFile.isVideo ? "video/*" : "audio/*");
                            share.putExtra(Intent.EXTRA_STREAM, savedUri);
                            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(share, "Share trimmed video"));
                        } catch (Exception e) {
                            Toast.makeText(EditorActivity.this, "Could not open share sheet",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private String queryString(Uri uri, String column) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{column}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(column);
                if (idx >= 0) {
                    String s = cursor.getString(idx);
                    if (s != null && s.trim().length() > 0) return s;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /** Original file name from OpenableColumns, MediaStore, or the title as a fallback. */
    private String resolveOriginalDisplayName(Uri sourceUri) {
        if (videoFile != null && PlaylistRepository.getCustomTitle(this, videoFile.uri) != null) {
            return videoFile.title + ".mp4";
        }
        if (sourceUri != null) {
            String name = queryString(sourceUri, OpenableColumns.DISPLAY_NAME);
            if (name == null) name = queryString(sourceUri, MediaStore.MediaColumns.DISPLAY_NAME);
            if (name != null) return name;
        }
        String title = videoFile != null ? videoFile.title : null;
        if (title != null && title.trim().length() > 0) {
            return title.indexOf('.') >= 0 ? title : title + ".mp4";
        }
        return "video.mp4";
    }

    /** Best-effort same folder as the source (for example "Movies/Camera"), else null. */
    private String resolveSourceRelativePath(Uri sourceUri) {
        if (sourceUri == null) return null;
        String rel = null;
        if (Build.VERSION.SDK_INT >= 29) {
            rel = queryString(sourceUri, "relative_path");
        }
        if (rel != null) {
            while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.length() > 0) return rel;
        }
        String data = queryString(sourceUri, MediaStore.MediaColumns.DATA);
        if (data != null) {
            File parent = new File(data).getParentFile();
            String external = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (parent != null && data.startsWith(external)) {
                String r = parent.getAbsolutePath().substring(external.length());
                while (r.startsWith("/")) r = r.substring(1);
                if (r.length() > 0) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Build a VideoFile from a URI (used for VIEW / SEND intents)
    // ------------------------------------------------------------------

    private VideoFile loadMediaFileFromUri(Uri uri) {
        String title = "Unknown";
        long duration = 0L;
        long size = 0L;
        boolean isVideo = false;
        String resolution = null;
        String artist = null;

        try {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex != -1) {
                        String n = cursor.getString(nameIndex);
                        if (n != null) title = n;
                    }
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            title = PlaylistRepository.stripExtension(title);

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) duration = Long.parseLong(durationStr);

                String hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
                isVideo = hasVideoStr != null;

                if (isVideo) {
                    String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null && h != null) resolution = w + "x" + h;
                } else {
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                }

                if (!isVideo) {
                    String mime = getContentResolver().getType(uri);
                    if (mime != null && mime.startsWith("video/")) isVideo = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }

            return new VideoFile(System.currentTimeMillis(), uri, title, duration, size,
                    resolution, artist, System.currentTimeMillis() / 1000L, isVideo);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
