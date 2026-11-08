package kr.toolboxapp.tbai;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 웹 화면(index.html) ↔ 기기 AI 엔진(TbEngine) 연결.
 * JS: Capacitor.Plugins.TbAI.load({name, files, q8, heavy}) / run({name, inputs: {이름: {dims, data(base64)}}})
 */
@CapacitorPlugin(name = "TbAI")
public class TbAIPlugin extends Plugin {
    private static final String TAG = "TbAI";
    private TbEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TbEngine engine() {
        if (engine == null) engine = new TbEngine(getContext());
        return engine;
    }

    /** 결과 저장: {name, mime, data(base64), share?} → {saved: true, where} 또는 공유 창 */
    @PluginMethod
    public void save(final PluginCall call) {
        final String name = call.getString("name", "툴박스");
        final String mime = call.getString("mime", "application/octet-stream");
        final String data = call.getString("data");
        final boolean share = Boolean.TRUE.equals(call.getBoolean("share", false));
        if (data == null) { call.reject("no data"); return; }
        worker.execute(() -> {
            try {
                byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
                String where = share ? null : TbSaver.saveToStorage(getContext(), name, mime, bytes);
                JSObject r = new JSObject();
                if (where != null) {
                    r.put("saved", true);
                    r.put("where", where);
                } else {
                    getContext().startActivity(TbSaver.shareIntent(getContext(), name, mime, bytes));
                    r.put("shared", true);
                }
                call.resolve(r);
            } catch (Throwable e) {
                Log.e(TAG, "save", e);
                call.reject("save failed: " + e.getMessage());
            }
        });
    }

    // ---- 다른 앱(갤러리·카톡·파일)에서 '공유 → 툴박스'로 보낸 파일 ----
    private Intent pendingShare;

    // ---- 앱 링크(kr.toolboxapp.app://login#…) — 브라우저에서 로그인하고 돌아올 때 ----
    private String pendingUrl;

    @Override
    public void load() {
        Intent i = getActivity() != null ? getActivity().getIntent() : null;
        if (isShare(i)) pendingShare = i;
        if (isAppLink(i)) pendingUrl = i.getDataString();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (isShare(intent)) {
            pendingShare = intent;
            notifyListeners("shared", new JSObject(), true);
        }
        if (isAppLink(intent)) {
            JSObject d = new JSObject();
            d.put("url", intent.getDataString());
            notifyListeners("url", d, true);
        }
    }

    private static boolean isAppLink(Intent i) {
        return i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getDataString() != null && i.getDataString().startsWith("kr.toolboxapp.app:");
    }

    /** 앱이 꺼져 있다가 링크로 열렸을 때 그 링크 (한 번 꺼내면 비워요) */
    @PluginMethod
    public void takeUrl(PluginCall call) {
        JSObject r = new JSObject();
        if (pendingUrl != null) r.put("url", pendingUrl);
        pendingUrl = null;
        call.resolve(r);
    }

    /** 기본 브라우저로 열기 (구글 로그인은 앱 안 화면에서 막혀서 브라우저에서 해요) */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) { call.reject("bad url"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Throwable e) {
            call.reject("open failed: " + e.getMessage());
        }
    }

    private static boolean isShare(Intent i) {
        return i != null && (Intent.ACTION_SEND.equals(i.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()));
    }

    /** 받은 파일을 꺼내 줘요 (한 번 꺼내면 비워요): {files: [{name, mime, data(base64)}], text?} */
    @PluginMethod
    public void takeShared(final PluginCall call) {
        final Intent i = pendingShare;
        pendingShare = null;
        worker.execute(() -> {
            JSObject r = new JSObject();
            JSArray files = new JSArray();
            try {
                if (i != null) {
                    List<Uri> uris = new ArrayList<>();
                    if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
                        ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                        if (l != null) uris.addAll(l);
                    } else {
                        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
                        if (u != null) uris.add(u);
                    }
                    long total = 0;
                    for (Uri u : uris) {
                        String name = "파일", mime = getContext().getContentResolver().getType(u);
                        try (Cursor c = getContext().getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) name = n; }
                        } catch (Throwable ignore) { }
                        try (InputStream in = getContext().getContentResolver().openInputStream(u)) {
                            if (in == null) continue;
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bo.write(buf, 0, n);
                                total += n;
                                if (total > 150L * 1024 * 1024) throw new Exception("파일이 너무 커요 (150MB까지)");
                            }
                            JSObject f = new JSObject();
                            f.put("name", name);
                            f.put("mime", mime == null ? "application/octet-stream" : mime);
                            f.put("data", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                            files.put(f);
                        }
                    }
                    String text = i.getStringExtra(Intent.EXTRA_TEXT);
                    if (text != null) r.put("text", text);
                }
                r.put("files", files);
                call.resolve(r);
            } catch (Throwable e) {
                Log.e(TAG, "takeShared", e);
                call.reject("share failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        r.put("platform", "android");
        r.put("memory", (double) engine().totalMemory());
        r.put("cores", Runtime.getRuntime().availableProcessors());
        r.put("ort", "1.30.0");
        call.resolve(r);
    }

    @PluginMethod
    public void load(final PluginCall call) {
        final String name = call.getString("name");
        final JSArray filesArr = call.getArray("files");
        final boolean q8 = Boolean.TRUE.equals(call.getBoolean("q8", false));
        final boolean heavy = Boolean.TRUE.equals(call.getBoolean("heavy", false));
        if (name == null || filesArr == null || filesArr.length() == 0) {
            call.reject("bad args");
            return;
        }
        worker.execute(() -> {
            try {
                List<String> files = new ArrayList<>();
                for (int i = 0; i < filesArr.length(); i++) files.add(filesArr.getString(i));
                TbEngine.Loaded l = engine().load(name, files, q8, heavy);
                JSObject r = new JSObject();
                r.put("inputs", new JSArray(l.inputs));
                r.put("outputs", new JSArray(l.outputs));
                r.put("accel", l.accel);
                call.resolve(r);
            } catch (Throwable e) {
                String m = String.valueOf(e.getMessage());
                if (!m.contains("TB_LOWMEM")) Log.e(TAG, "load", e);
                call.reject(m.contains("TB_LOWMEM") ? "TB_LOWMEM" : "load failed: " + m);
            }
        });
    }

    @PluginMethod
    public void run(final PluginCall call) {
        final String name = call.getString("name");
        final JSObject inputs = call.getObject("inputs");
        if (name == null || inputs == null) {
            call.reject("bad args");
            return;
        }
        worker.execute(() -> {
            try {
                Map<String, TbEngine.Tensor> feeds = new HashMap<>();
                Iterator<String> it = inputs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject t = inputs.getJSONObject(k);
                    JSONArray d = t.getJSONArray("dims");
                    long[] shape = new long[d.length()];
                    for (int i = 0; i < shape.length; i++) shape[i] = d.getLong(i);
                    feeds.put(k, new TbEngine.Tensor(shape, TbEngine.b64ToFloats(t.getString("data"))));
                }
                Map<String, TbEngine.Tensor> res = engine().run(name, feeds);
                JSObject outs = new JSObject();
                for (Map.Entry<String, TbEngine.Tensor> e : res.entrySet()) {
                    JSArray dims = new JSArray();
                    for (long v : e.getValue().shape) dims.put(v);
                    JSObject o = new JSObject();
                    o.put("dims", dims);
                    o.put("data", TbEngine.floatsToB64(e.getValue().data));
                    outs.put(e.getKey(), o);
                }
                JSObject r = new JSObject();
                r.put("outputs", outs);
                call.resolve(r);
            } catch (Throwable e) {
                Log.e(TAG, "run", e);
                call.reject("run failed: " + e.getMessage());
            }
        });
    }
}
