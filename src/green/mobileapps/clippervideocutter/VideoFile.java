package green.mobileapps.clippervideocutter;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/** Immutable data model for one video (or audio) item in the list. */
public class VideoFile implements Parcelable {

    public final long id;
    public final Uri uri;
    public final String title;
    public final long duration;
    public final long size;
    public final String resolution;
    public final String artist;
    public final long dateAdded;
    public final boolean isVideo;
    /** True when the SAF/MediaStore URI can no longer be opened (needs re-link). */
    public final boolean unavailable;

    public VideoFile(long id, Uri uri, String title, long duration, long size,
                     String resolution, String artist, long dateAdded,
                     boolean isVideo, boolean unavailable) {
        this.id = id;
        this.uri = uri;
        this.title = title;
        this.duration = duration;
        this.size = size;
        this.resolution = resolution;
        this.artist = artist;
        this.dateAdded = dateAdded;
        this.isVideo = isVideo;
        this.unavailable = unavailable;
    }

    public VideoFile(long id, Uri uri, String title, long duration, long size,
                     String resolution, String artist, long dateAdded, boolean isVideo) {
        this(id, uri, title, duration, size, resolution, artist, dateAdded, isVideo, false);
    }

    public VideoFile withTitle(String newTitle) {
        return new VideoFile(id, uri, newTitle, duration, size, resolution, artist,
                dateAdded, isVideo, unavailable);
    }

    public VideoFile withDurationAndResolution(long newDuration, String newResolution) {
        return new VideoFile(id, uri, title, newDuration, size, newResolution, artist,
                dateAdded, isVideo, unavailable);
    }

    // ---- Parcelable ----

    private VideoFile(Parcel p) {
        id = p.readLong();
        uri = (Uri) p.readParcelable(Uri.class.getClassLoader());
        title = p.readString();
        duration = p.readLong();
        size = p.readLong();
        resolution = p.readString();
        artist = p.readString();
        dateAdded = p.readLong();
        isVideo = p.readByte() != 0;
        unavailable = p.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeLong(id);
        p.writeParcelable(uri, flags);
        p.writeString(title);
        p.writeLong(duration);
        p.writeLong(size);
        p.writeString(resolution);
        p.writeString(artist);
        p.writeLong(dateAdded);
        p.writeByte((byte) (isVideo ? 1 : 0));
        p.writeByte((byte) (unavailable ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<VideoFile> CREATOR = new Parcelable.Creator<VideoFile>() {
        @Override
        public VideoFile createFromParcel(Parcel p) {
            return new VideoFile(p);
        }

        @Override
        public VideoFile[] newArray(int size) {
            return new VideoFile[size];
        }
    };
}
