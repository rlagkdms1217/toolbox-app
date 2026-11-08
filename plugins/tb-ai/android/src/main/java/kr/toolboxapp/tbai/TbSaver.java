package kr.toolboxapp.tbai;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 결과 파일 저장 (앱 안에서는 웹의 '다운로드'가 안 돼서 여기서 대신 저장해요).
 * 안드로이드 10 이상: 사진 → 갤러리(Pictures/툴박스), 영상 → Movies/툴박스, 나머지 → Download/툴박스
 * 그보다 오래된 폰: 공유 창을 띄워서 원하는 곳에 저장
 */
public class TbSaver {
    public static final String FOLDER = "툴박스";

    /** 저장한 위치(사람이 읽는 말)를 돌려줘요. 오래된 폰이면 null → 공유 창으로 */
    public static String saveToStorage(Context ctx, String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri coll;
        String where;
        if (mime.startsWith("image/")) {
            coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            where = Environment.DIRECTORY_PICTURES + "/" + FOLDER;
        } else if (mime.startsWith("video/")) {
            coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            where = Environment.DIRECTORY_MOVIES + "/" + FOLDER;
        } else if (mime.startsWith("audio/")) {
            coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            where = Environment.DIRECTORY_MUSIC + "/" + FOLDER;
        } else {
            coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            where = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
        }
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, where);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(coll, v);
        if (uri == null) throw new Exception("저장할 곳을 만들지 못했어요");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("파일을 열지 못했어요");
            os.write(data);
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return where;
    }

    /** 공유 창 (앱 캐시에 임시 파일을 만들어 넘겨요) */
    public static Intent shareIntent(Context ctx, String name, String mime, byte[] data) throws Exception {
        File dir = new File(ctx.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("임시 폴더를 못 만들었어요");
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, name);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return chooser;
    }
}
