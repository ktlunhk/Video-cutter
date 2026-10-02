package green.mobileapps.clippervideocutter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity
        implements SearchView.OnQueryTextListener, PlaylistRepository.Listener {

    // ---- sorting ----

    private enum SortBy {DATE, TITLE, DURATION, SIZE}

    private static final class SortState {
        final SortBy by;
        final boolean ascending;

        SortState(SortBy by, boolean ascending) {
            this.by = by;
            this.ascending = ascending;
        }
    }

    private static final int REQ_PICK = 101;
    private static final int REQ_RELINK = 102;
    private static final int REQ_WRITE = 103;
    private static final int NO_POSITION = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    private ListView listView;
    private TextView textStatus;
    private SearchView searchView;
    private ImageButton buttonSort;
    private ImageButton buttonSortDirection;
    private ImageButton buttonBackEdit;
    private TextView textEditTitle;
    private MusicAdapter adapter;

    private List<VideoFile> fullList = new ArrayList<VideoFile>();
    private String currentQuery = "";
    private SortState sortState = new SortState(SortBy.DATE, false);

    private VideoFile pendingUpdateFile;
    private String pendingUpdateTitle;
    private VideoFile pendingRelinkFile;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.main_activity);

        listView = (ListView) findViewById(R.id.list_view_music);
        textStatus = (TextView) findViewById(R.id.text_status);
        searchView = (SearchView) findViewById(R.id.search_view_music);
        buttonSort = (ImageButton) findViewById(R.id.button_sort);
        buttonSortDirection = (ImageButton) findViewById(R.id.button_sort_direction);
        buttonBackEdit = (ImageButton) findViewById(R.id.button_back_edit);
        textEditTitle = (TextView) findViewById(R.id.text_edit_title);

        adapter = new MusicAdapter();
        listView.setAdapter(adapter);

        searchView.setOnQueryTextListener(this);

        buttonBackEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleExitEditMode();
            }
        });
        buttonSortDirection.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortState = new SortState(sortState.by, !sortState.ascending);
                updateSortIcon();
                applySortAndFilter();
            }
        });
        buttonSort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu(v);
            }
        });
        findViewById(R.id.fab_add_video).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openVideoPicker(REQ_PICK, true);
            }
        });

        updateSortIcon();
        PlaylistRepository.addListener(this);
        onPlaylistChanged(PlaylistRepository.getFullPlaylist());

        // Restore previously added videos (persistable SAF grants survive restarts)
        restoreSavedPlaylist();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideKeyboardAndClearFocus();
        if (adapter.getEditingPosition() != NO_POSITION) exitEditingMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlaylistRepository.removeListener(this);
        ioExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (adapter.getEditingPosition() != NO_POSITION) {
            handleExitEditMode();
        } else {
            super.onBackPressed();
        }
    }

    // The overflow (three dot) menu is not shown on the main screen
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    // ------------------------------------------------------------------
    // Playlist state, sorting and filtering
    // ------------------------------------------------------------------

    @Override
    public void onPlaylistChanged(List<VideoFile> files) {
        fullList = files;
        applySortAndFilter();
    }

    private static int compareLong(long a, long b) {
        return a < b ? -1 : (a == b ? 0 : 1);
    }

    private void applySortAndFilter() {
        List<VideoFile> sorted = new ArrayList<VideoFile>(fullList);
        final SortBy by = sortState.by;
        Collections.sort(sorted, new Comparator<VideoFile>() {
            @Override
            public int compare(VideoFile a, VideoFile b) {
                switch (by) {
                    case TITLE:
                        return String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title);
                    case DURATION:
                        return compareLong(a.duration, b.duration);
                    case SIZE:
                        return compareLong(a.size, b.size);
                    case DATE:
                    default:
                        return compareLong(a.dateAdded, b.dateAdded);
                }
            }
        });
        if (!sortState.ascending) Collections.reverse(sorted);

        List<VideoFile> filtered = new ArrayList<VideoFile>();
        String q = currentQuery.toLowerCase(Locale.getDefault()).trim();
        for (VideoFile f : sorted) {
            if (q.length() == 0
                    || f.title.toLowerCase(Locale.getDefault()).contains(q)
                    || (f.artist != null && f.artist.toLowerCase(Locale.getDefault()).contains(q))) {
                filtered.add(f);
            }
        }

        adapter.setList(filtered);
        if (!filtered.isEmpty()) {
            listView.setVisibility(View.VISIBLE);
            textStatus.setVisibility(View.GONE);
        } else if (!fullList.isEmpty()) {
            showStatus("No matches.");
        } else {
            showStatus("Tap + to add videos");
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, SortBy.DATE.ordinal(), 0, "Date Added");
        popup.getMenu().add(0, SortBy.TITLE.ordinal(), 1, "Title");
        popup.getMenu().add(0, SortBy.DURATION.ordinal(), 2, "Length");
        popup.getMenu().add(0, SortBy.SIZE.ordinal(), 3, "Size");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                SortBy[] all = SortBy.values();
                int id = item.getItemId();
                if (id < 0 || id >= all.length) return false;
                SortBy chosen = all[id];
                boolean newAscending;
                if (chosen == sortState.by) {
                    newAscending = !sortState.ascending;
                } else {
                    newAscending = false;
                }
                sortState = new SortState(chosen, newAscending);
                updateSortIcon();
                applySortAndFilter();
                return true;
            }
        });
        popup.show();
    }

    private void updateSortIcon() {
        buttonSortDirection.setImageResource(
                sortState.ascending ? R.drawable.ascending_24px : R.drawable.descending_24px);
    }

    private void showStatus(String message) {
        listView.setVisibility(View.GONE);
        textStatus.setVisibility(View.VISIBLE);
        textStatus.setText(message);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        hideKeyboardAndClearFocus();
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (searchView.isEnabled()) {
            currentQuery = newText == null ? "" : newText;
            applySortAndFilter();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Picking, restoring and re-linking videos
    // ------------------------------------------------------------------

    private void openVideoPicker(int requestCode, boolean multiple) {
        // ACTION_OPEN_DOCUMENT opens the system file browser. No storage permission is needed.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePersistablePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Provider may not support persistable grants. A temporary grant still works.
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null) return;
            final List<Uri> uris = new ArrayList<Uri>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final List<VideoFile> added = new ArrayList<VideoFile>();
                    for (Uri uri : uris) {
                        takePersistablePermission(uri);
                        VideoFile v = PlaylistRepository.loadVideoFromUri(getApplicationContext(), uri);
                        if (v != null) added.add(v);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!added.isEmpty()) {
                                PlaylistRepository.addFiles(added, MainActivity.this);
                                Toast.makeText(MainActivity.this,
                                        "Added " + added.size() + " video(s)",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "Could not load selected videos.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            });

        } else if (requestCode == REQ_RELINK) {
            final VideoFile old = pendingRelinkFile;
            pendingRelinkFile = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || old == null) {
                return;
            }
            final Uri uri = data.getData();
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    takePersistablePermission(uri);
                    final VideoFile video =
                            PlaylistRepository.loadVideoFromUri(getApplicationContext(), uri);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (video != null) {
                                PlaylistRepository.replaceFile(old.id, video, MainActivity.this);
                                Toast.makeText(MainActivity.this, "Re-linked: " + video.title,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "Could not open selected file",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            });

        } else if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK) {
                executePendingMetadataUpdate();
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show();
                pendingUpdateFile = null;
                pendingUpdateTitle = null;
                exitEditingMode();
            }
        }
    }

    private void restoreSavedPlaylist() {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Context app = getApplicationContext();
                List<Uri> uris = PlaylistRepository.loadSavedUris(app);
                if (uris.isEmpty()) return;
                final List<VideoFile> restored = new ArrayList<VideoFile>();
                for (Uri uri : uris) {
                    takePersistablePermission(uri);
                    boolean canOpen = false;
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in != null) {
                            canOpen = true;
                            in.close();
                        }
                    } catch (Exception ignored) {
                    }
                    if (canOpen) {
                        VideoFile v = PlaylistRepository.loadVideoFromUri(app, uri);
                        // Readable stream but metadata failed: keep it as unavailable for re-link
                        restored.add(v != null ? v : PlaylistRepository.unavailablePlaceholder(uri));
                    } else {
                        restored.add(PlaylistRepository.unavailablePlaceholder(uri));
                    }
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!restored.isEmpty()) {
                            PlaylistRepository.setFiles(restored, MainActivity.this);
                        }
                    }
                });
            }
        });
    }

    public void relinkVideo(VideoFile file) {
        pendingRelinkFile = file;
        openVideoPicker(REQ_RELINK, false);
    }

    // ------------------------------------------------------------------
    // Removing a video from the list
    // ------------------------------------------------------------------

    private void removeVideo(final VideoFile videoFile) {
        if (adapter.getEditingPosition() != NO_POSITION) exitEditingMode();
        new AlertDialog.Builder(this)
                .setTitle("Remove video?")
                .setMessage("Remove \"" + videoFile.title
                        + "\" from the list? The original file on disk is not deleted.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PlaylistRepository.removeFile(videoFile.id, MainActivity.this);
                        Toast.makeText(MainActivity.this, "Removed: " + videoFile.title,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Opening the editor
    // ------------------------------------------------------------------

    private void startVideoEditor(VideoFile file) {
        if (adapter.getEditingPosition() != NO_POSITION) {
            exitEditingMode();
            return;
        }
        if (file.unavailable) {
            relinkVideo(file);
            return;
        }
        hideKeyboardAndClearFocus();

        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra("EXTRA_MEDIA_FILE", file);
        // Intent data plus the flag lets the URI grant transfer to the next activity
        intent.setData(file.uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Title editing (long press)
    // ------------------------------------------------------------------

    private void startEditing(int position) {
        searchView.setVisibility(View.GONE);
        buttonSort.setVisibility(View.GONE);
        buttonSortDirection.setVisibility(View.GONE);
        buttonBackEdit.setVisibility(View.VISIBLE);
        textEditTitle.setVisibility(View.VISIBLE);
        searchView.setEnabled(false);
        searchView.clearFocus();

        adapter.setEditingPosition(position);
        final int pos = position;
        listView.post(new Runnable() {
            @Override
            public void run() {
                EditText et = findTitleEditor(pos);
                if (et != null) {
                    et.requestFocus();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                }
            }
        });
        Toast.makeText(this, "Editing title...", Toast.LENGTH_LONG).show();
    }

    private EditText findTitleEditor(int position) {
        int child = position - listView.getFirstVisiblePosition();
        if (child < 0 || child >= listView.getChildCount()) return null;
        View row = listView.getChildAt(child);
        return row == null ? null : (EditText) row.findViewById(R.id.edit_text_title);
    }

    private void handleExitEditMode() {
        int position = adapter.getEditingPosition();
        if (position == NO_POSITION) return;
        VideoFile file = adapter.getItemOrNull(position);
        EditText et = findTitleEditor(position);
        if (file != null && et != null) {
            saveEditAndExit(file, et.getText().toString().trim());
        } else {
            exitEditingMode();
        }
    }

    private void saveEditAndExit(VideoFile videoFile, String newTitle) {
        if (newTitle.length() == 0 || newTitle.equals(videoFile.title)) {
            exitEditingMode();
            return;
        }
        pendingUpdateFile = videoFile;
        pendingUpdateTitle = newTitle;
        requestMetadataWritePermission(videoFile.uri);
    }

    /** Saves the new title inside the app (always works), then tries the system database too. */
    private void requestMetadataWritePermission(Uri uri) {
        executePendingMetadataUpdate();
    }

    private void executePendingMetadataUpdate() {
        final VideoFile file = pendingUpdateFile;
        final String newTitle = pendingUpdateTitle;
        pendingUpdateFile = null;
        pendingUpdateTitle = null;
        if (file == null || newTitle == null) {
            exitEditingMode();
            return;
        }
        PlaylistRepository.saveCustomTitle(getApplicationContext(), file.uri, newTitle);
        PlaylistRepository.updateFile(file.withTitle(newTitle));
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        exitEditingMode();

        // Best effort only: the picked file usually cannot be changed in the system database
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentValues values = new ContentValues();
                    values.put(file.isVideo ? MediaStore.Video.Media.TITLE
                            : MediaStore.Audio.Media.TITLE, newTitle);
                    getContentResolver().update(file.uri, values, null, null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void exitEditingMode() {
        hideKeyboardAndClearFocus();
        adapter.setEditingPosition(NO_POSITION);
        searchView.setVisibility(View.VISIBLE);
        buttonSort.setVisibility(View.VISIBLE);
        buttonSortDirection.setVisibility(View.VISIBLE);
        buttonBackEdit.setVisibility(View.GONE);
        textEditTitle.setVisibility(View.GONE);
        searchView.setEnabled(true);
    }

    private void hideKeyboardAndClearFocus() {
        searchView.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        View focus = getCurrentFocus();
        android.os.IBinder token = focus != null ? focus.getWindowToken()
                : listView.getWindowToken();
        imm.hideSoftInputFromWindow(token, 0);
    }

    // ------------------------------------------------------------------
    // Menu handlers (kept from the original project)
    // ------------------------------------------------------------------

    public void onAboutClick(MenuItem menuItem) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/")));
    }

    public void onPrivacyClick(MenuItem menuItem) {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://mobileapps.green/privacy-policy")));
    }

    public void onRateClick(MenuItem menuItem) {
        String appPackageName = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + appPackageName)));
        } catch (ActivityNotFoundException anfe) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)));
        }
    }

    // ------------------------------------------------------------------
    // Thumbnails
    // ------------------------------------------------------------------

    private static Bitmap getVideoThumbnailSafe(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap original = retriever.getFrameAtTime();
            if (original == null) return null;

            int width = original.getWidth();
            int height = original.getHeight();
            int target = 144;
            float scale = Math.max((float) target / width, (float) target / height);
            int scaledW = (int) (width * scale);
            int scaledH = (int) (height * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(original, scaledW, scaledH, true);

            int x = (scaledW - target) / 2;
            int y = (scaledH - target) / 2;
            Bitmap result;
            if (x >= 0 && y >= 0 && x + target <= scaledW && y + target <= scaledH) {
                result = Bitmap.createBitmap(scaled, x, y, target, target);
            } else {
                result = scaled;
            }
            if (original != result) original.recycle();
            if (scaled != result) scaled.recycle();
            return result;
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            System.gc();
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap roundCorners(Bitmap src, float radius) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    private static String formatDuration(long durationMs) {
        long seconds = durationMs / 1000L;
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    // ------------------------------------------------------------------
    // List adapter
    // ------------------------------------------------------------------

    private final class MusicAdapter extends BaseAdapter {

        private List<VideoFile> items = new ArrayList<VideoFile>();
        private int editingPosition = NO_POSITION;

        void setList(List<VideoFile> newList) {
            items = newList;
            notifyDataSetChanged();
        }

        int getEditingPosition() {
            return editingPosition;
        }

        void setEditingPosition(int newPosition) {
            editingPosition = newPosition;
            notifyDataSetChanged();
        }

        VideoFile getItemOrNull(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_media_file, parent, false);
            }
            final View row = convertView;
            final VideoFile file = items.get(position);
            final boolean isEditing = position == editingPosition;

            View card = row.findViewById(R.id.item_card);
            final ImageView image = (ImageView) row.findViewById(R.id.image_album_art);
            TextView title = (TextView) row.findViewById(R.id.text_title);
            TextView duration = (TextView) row.findViewById(R.id.text_duration);
            TextView details = (TextView) row.findViewById(R.id.text_details);
            EditText editTitle = (EditText) row.findViewById(R.id.edit_text_title);
            View saveButton = row.findViewById(R.id.button_save_edit);
            View removeButton = row.findViewById(R.id.button_remove);

            card.setBackgroundResource(position % 2 == 0 ? R.drawable.bg_card_alt : R.drawable.bg_card);

            bindThumbnail(image, file);

            title.setText(file.title);
            if (file.unavailable) {
                duration.setText("Unavailable");
                details.setText("Tap to re-link file");
            } else {
                duration.setText(formatDuration(file.duration));
                String res = file.resolution != null ? file.resolution : "Video";
                String sizeStr = Formatter.formatFileSize(MainActivity.this, file.size);
                details.setText(res + " \u2726 " + sizeStr);
            }
            editTitle.setText(file.title);

            int viewMode = isEditing ? View.GONE : View.VISIBLE;
            title.setVisibility(viewMode);
            duration.setVisibility(viewMode);
            details.setVisibility(viewMode);
            removeButton.setVisibility(viewMode);
            editTitle.setVisibility(isEditing ? View.VISIBLE : View.GONE);
            saveButton.setVisibility(isEditing ? View.VISIBLE : View.GONE);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isEditing) return;
                    if (file.unavailable) {
                        relinkVideo(file);
                    } else {
                        startVideoEditor(file);
                    }
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!isEditing && !file.unavailable) {
                        startEditing(position);
                        return true;
                    }
                    return false;
                }
            });
            final EditText editTitleFinal = editTitle;
            saveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveEditAndExit(file, editTitleFinal.getText().toString().trim());
                }
            });
            removeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isEditing) removeVideo(file);
                }
            });
            return row;
        }

        /** Memory cache, then disk cache, then generate in the background. */
        private void bindThumbnail(final ImageView image, final VideoFile file) {
            image.setTag(Long.valueOf(file.id));
            if (file.unavailable) {
                image.setImageResource(R.drawable.vide_shadow);
                return;
            }
            Bitmap cached = PlaylistRepository.thumbnailCache.get(file.id);
            if (cached != null) {
                image.setImageBitmap(roundCorners(cached, 24f));
                return;
            }
            image.setImageResource(R.drawable.vide_shadow);
            thumbExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    Context app = getApplicationContext();
                    Bitmap bitmap = PlaylistRepository.loadDiskThumbnail(app, file.id);
                    if (bitmap == null) {
                        bitmap = getVideoThumbnailSafe(app, file.uri);
                        if (bitmap != null) PlaylistRepository.saveDiskThumbnail(app, file.id, bitmap);
                    }
                    if (bitmap != null) PlaylistRepository.thumbnailCache.put(file.id, bitmap);
                    final Bitmap rounded = bitmap != null ? roundCorners(bitmap, 24f) : null;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Object tag = image.getTag();
                            if (tag instanceof Long && ((Long) tag).longValue() == file.id
                                    && rounded != null) {
                                image.setImageBitmap(rounded);
                            }
                        }
                    });
                }
            });
        }
    }
}
