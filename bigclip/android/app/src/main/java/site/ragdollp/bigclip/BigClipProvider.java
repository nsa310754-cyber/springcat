package site.ragdollp.bigclip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.ClipDescription;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** クリップボードに載せた大きなテキストを貼り付け先アプリへストリームで渡す。 */
public class BigClipProvider extends ContentProvider {
    private static final String MIME = "text/plain";

    @Override public boolean onCreate() { return true; }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // パストラバーサル防止: 自分が書いたファイル名の形式だけ許可
        if (name == null || !name.matches("clip-\\d+\\.txt")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(ClipStore.dir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException("期限切れのクリップです: " + uri);
        return f;
    }

    @Override public String getType(Uri uri) { return MIME; }

    @Override public String[] getStreamTypes(Uri uri, String filter) {
        return ClipDescription.compareMimeTypes(MIME, filter) ? new String[]{MIME} : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
