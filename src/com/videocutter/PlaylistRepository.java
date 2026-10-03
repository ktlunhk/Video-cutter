package com.videocutter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the list of videos the user added, persists their URIs across restarts,
 * and caches thumbnails. Simple static holder with listeners.
 */
public final class PlaylistRepository {

    public interface Listener {
        /** Always delivered on the main thread with a snapshot of the whole list. */
        void onPlaylistChanged(List<VideoFile> files);
    }

    private static final String PREFS_NAME = "video_cutter_playlist";
    private static final String KEY_URIS = "saved_uris";
    private static final String TITLES_PREFS = "video_cutter_titles";

    private static final List<VideoFile> files = new ArrayList<VideoFile>();
    private static final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static final LruCache<Long, Bitmap> thumbnailCache =
            new LruCache<Long, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(Long key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private PlaylistRepository() {
    }

    // ---- listeners ----

    public static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        final List<VideoFile> snapshot;
        synchronized (files) {
            snapshot = new ArrayList<VideoFile>(files);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onPlaylistChanged(snapshot);
                }
            }
        });
    }

    // ---- list operations ----

    public static List<VideoFile> getFullPlaylist() {
        synchronized (files) {
            return new ArrayList<VideoFile>(files);
        }
    }

    public static void setFiles(List<VideoFile> newFiles, Context context) {
        synchronized (files) {
            files.clear();
            files.addAll(newFiles);
        }
        notifyChanged();
        if (context != null) persistUris(context);
    }

    public static void replaceFile(long oldId, VideoFile newFile, Context context) {
        boolean changed = false;
        synchronized (files) {
            int index = indexOfId(oldId);
            if (index != -1) {
                files.set(index, newFile);
                changed = true;
            }
        }
        if (changed) {
            thumbnailCache.remove(oldId);
            if (context != null) removeDiskThumbnail(context, oldId);
            notifyChanged();
            if (context != null) persistUris(context);
        }
    }

    public static void addFiles(List<VideoFile> newFiles, Context context) {
        int added = 0;
        synchronized (files) {
            // Dedupe by URI string, because SAF ids can collide or be 0
            Set<String> existing = new HashSet<String>();
            for (VideoFile f : files) existing.add(f.uri.toString());
            for (VideoFile f : newFiles) {
                String key = f.uri.toString();
                if (!existing.contains(key)) {
                    files.add(f);
                    existing.add(key);
                    added++;
                }
            }
        }
        if (added > 0) {
            notifyChanged();
            if (context != null) persistUris(context);
        }
    }

    public static void removeFile(long fileId, Context context) {
        boolean changed = false;
        synchronized (files) {
            int index = indexOfId(fileId);
            if (index != -1) {
                files.remove(index);
                changed = true;
            }
        }
        if (changed) {
            thumbnailCache.remove(fileId);
            if (context != null) removeDiskThumbnail(context, fileId);
            notifyChanged();
            if (context != null) persistUris(context);
        }
    }

    public static void updateFile(VideoFile updated) {
        boolean changed = false;
        synchronized (files) {
            int index = indexOfId(updated.id);
            if (index != -1) {
                files.set(index, updated);
                changed = true;
            }
        }
        if (changed) notifyChanged();
    }

    private static int indexOfId(long id) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).id == id) return i;
        }
        return -1;
    }

    // ---- custom titles (kept inside the app, so renaming never needs file write access) ----

    public static String getCustomTitle(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        String t = context.getSharedPreferences(TITLES_PREFS, Context.MODE_PRIVATE)
                .getString(uri.toString(), null);
        return (t != null && t.trim().length() > 0) ? t : null;
    }

    public static void saveCustomTitle(Context context, Uri uri, String title) {
        if (context == null || uri == null || title == null) return;
        context.getSharedPreferences(TITLES_PREFS, Context.MODE_PRIVATE)
                .edit().putString(uri.toString(), title).commit();
    }

    // ---- persistence ----

    public static List<Uri> loadSavedUris(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_URIS, new HashSet<String>());
        List<Uri> result = new ArrayList<Uri>();
        if (set == null) return result;
        for (String s : set) {
            try {
                result.add(Uri.parse(s));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static void persistUris(Context context) {
        Set<String> set = new HashSet<String>();
        synchronized (files) {
            for (VideoFile f : files) set.add(f.uri.toString());
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_URIS, set)
                .apply();
    }

    // ---- thumbnails ----

    private static File thumbDir(Context context) {
        File dir = new File(context.getCacheDir(), "thumbnails");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static Bitmap loadDiskThumbnail(Context context, long id) {
        try {
            File file = new File(thumbDir(context), id + ".jpg");
            if (!file.exists()) return null;
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveDiskThumbnail(Context context, long id, Bitmap bitmap) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(thumbDir(context), id + ".jpg"));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static void removeDiskThumbnail(Context context, long id) {
        try {
            new File(thumbDir(context), id + ".jpg").delete();
        } catch (Exception ignored) {
        }
    }

    // ---- loading a VideoFile from a picked URI ----

    public static long stableIdFor(Uri uri) {
        return ((long) uri.toString().hashCode()) & 0x7FFFFFFFFFFFFFFFL;
    }

    public static String fallbackTitleFor(Uri uri) {
        String seg = uri.getLastPathSegment();
        String name = "Video";
        if (seg != null) {
            int slash = seg.lastIndexOf('/');
            name = slash >= 0 ? seg.substring(slash + 1) : seg;
        }
        return stripExtension(name);
    }

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.trim().length() == 0 ? name : base;
    }

    public static VideoFile unavailablePlaceholder(Uri uri) {
        return new VideoFile(stableIdFor(uri), uri, fallbackTitleFor(uri), 0L, 0L,
                null, null, System.currentTimeMillis() / 1000L, true, true);
    }

    /** Reads name, size, duration and resolution for a URI. Returns null on failure. */
    public static VideoFile loadVideoFromUri(Context context, Uri uri) {
        try {
            long id = stableIdFor(uri);
            String title = fallbackTitleFor(uri);
            long size = 0L;

            // OpenableColumns works for SAF document URIs
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIdx >= 0) {
                        String n = cursor.getString(nameIdx);
                        if (n != null && n.trim().length() > 0) title = stripExtension(n);
                    }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }

            // Prefer MediaStore metadata when available
            cursor = null;
            try {
                cursor = context.getContentResolver().query(uri,
                        new String[]{MediaStore.Video.Media.TITLE,
                                MediaStore.Video.Media.DISPLAY_NAME,
                                MediaStore.Video.Media.SIZE},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int titleIndex = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                    String t = titleIndex >= 0 ? cursor.getString(titleIndex) : null;
                    String d = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                    if (t != null && t.trim().length() > 0) {
                        title = t;
                    } else if (d != null && d.trim().length() > 0) {
                        title = d;
                    }
                    if (sizeIndex >= 0 && size <= 0L) size = cursor.getLong(sizeIndex);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }

            // Always read duration (and resolution) from the file itself
            long duration = 0L;
            String resolution = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) duration = Long.parseLong(d);
                String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (w != null && h != null) resolution = w + "x" + h;
            } catch (Exception ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }

            String custom = getCustomTitle(context, uri);
            if (custom != null) title = custom;
            return new VideoFile(id, uri, title, duration, size, resolution, null,
                    System.currentTimeMillis() / 1000L, true);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
