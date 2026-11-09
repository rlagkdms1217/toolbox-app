package kr.toolboxapp.tbai;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 툴박스 기기 AI 엔진 본체 (안드로이드). 플러그인(TbAIPlugin)과 자동 시험(androidTest)이 같이 써요.
 * ONNX Runtime 1.30 으로 모델을 돌려요. XNNPACK(ARM 최적화) → 안 되면 CPU 멀티코어.
 * 모델은 앱 안 public/models 에 웹과 같은 형식(8비트 압축 조각)으로 들어 있고,
 * 처음 한 번 원래 모델로 풀어서 캐시 폴더에 저장해 둬요.
 */
public class TbEngine {
    private static final String TAG = "TbAI";
    public static final long HEAVY_MIN_MEMORY = 5_500_000_000L;

    public static class Loaded {
        public final List<String> inputs, outputs;
        public final String accel;
        Loaded(List<String> i, List<String> o, String a) { inputs = i; outputs = o; accel = a; }
    }

    public static class Tensor {
        public final long[] shape;
        public final float[] data;
        public Tensor(long[] shape, float[] data) { this.shape = shape; this.data = data; }
    }

    private final Context ctx;
    private OrtEnvironment env;
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private final Map<String, String> accels = new HashMap<>();

    public TbEngine(Context ctx) { this.ctx = ctx; }

    public long totalMemory() {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static int threads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /** heavy=true 인데 메모리가 6GB 미만이면 "TB_LOWMEM" 예외 → 웹 화면이 기존 모델로 바꿔요 */
    public synchronized Loaded load(String name, List<String> files, boolean q8, boolean heavy) throws Exception {
        if (heavy && totalMemory() < HEAVY_MIN_MEMORY) throw new Exception("TB_LOWMEM");
        OrtSession s = sessions.get(name);
        if (s == null) {
            if (env == null) env = OrtEnvironment.getEnvironment();
            File f = modelFile(name, files, q8);
            String accel = "xnnpack";
            try {
                s = env.createSession(f.getAbsolutePath(), options(true));
            } catch (Throwable e) {
                Log.w(TAG, "XNNPACK 실패, CPU로: " + e.getMessage());
                accel = "cpu";
                s = env.createSession(f.getAbsolutePath(), options(false));
            }
            sessions.put(name, s);
            accels.put(name, accel);
        }
        return new Loaded(new ArrayList<>(s.getInputNames()), new ArrayList<>(s.getOutputNames()), accels.get(name));
    }

    /** 모델 내리기 (메모리 비우기). 큰 모델을 쓴 뒤 다른 큰 모델을 올릴 때 */
    public synchronized void unload(String name) {
        OrtSession s = sessions.remove(name);
        accels.remove(name);
        if (s != null) try { s.close(); } catch (Throwable ignore) { }
    }

    public synchronized void unloadAll() {
        for (String k : new ArrayList<>(sessions.keySet())) unload(k);
    }

    private OrtSession.SessionOptions options(boolean xnnpack) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (xnnpack) {
            o.setIntraOpNumThreads(1);   // XNNPACK 가 스레드를 쓰면 ORT 자체 스레드는 1개 (권장 설정)
            o.addXnnpack(Collections.singletonMap("intra_op_num_threads", String.valueOf(threads())));
        } else {
            o.setIntraOpNumThreads(threads());
        }
        return o;
    }

    public synchronized Map<String, Tensor> run(String name, Map<String, Tensor> inputs) throws Exception {
        OrtSession s = sessions.get(name);
        if (s == null) throw new Exception("not loaded");
        Map<String, OnnxTensor> feeds = new HashMap<>();
        try {
            for (Map.Entry<String, Tensor> e : inputs.entrySet()) {
                ByteBuffer bb = ByteBuffer.allocateDirect(e.getValue().data.length * 4).order(ByteOrder.nativeOrder());
                bb.asFloatBuffer().put(e.getValue().data);
                feeds.put(e.getKey(), OnnxTensor.createTensor(env, bb.asFloatBuffer(), e.getValue().shape));
            }
            Map<String, Tensor> out = new LinkedHashMap<>();
            try (OrtSession.Result res = s.run(feeds)) {
                for (Map.Entry<String, OnnxValue> e : res) {
                    OnnxTensor t = (OnnxTensor) e.getValue();
                    FloatBuffer fb = t.getFloatBuffer();
                    float[] arr = new float[fb.remaining()];
                    fb.get(arr);
                    out.put(e.getKey(), new Tensor(t.getInfo().getShape(), arr));
                }
            }
            return out;
        } finally {
            for (OnnxTensor t : feeds.values()) t.close();
        }
    }

    // --- 웹 ↔ 엔진 사이 변환 (리틀엔디언 float32 바이트를 base64로) ---
    public static float[] b64ToFloats(String b64) {
        byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
        float[] f = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }

    public static String floatsToB64(float[] f) {
        ByteBuffer ob = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        ob.asFloatBuffer().put(f);
        return Base64.encodeToString(ob.array(), Base64.NO_WRAP);
    }

    private byte[] readAsset(String file) throws Exception {
        try (InputStream in = ctx.getAssets().open("public/" + file)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private long assetSize(String file) {
        try (InputStream in = ctx.getAssets().open("public/" + file)) {
            long n = 0, k;
            byte[] buf = new byte[1 << 16];
            while ((k = in.read(buf)) > 0) n += k;
            return n;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 8비트 압축 조각 → 원래 ONNX 파일 (웹 index.html 의 loadModel 과 같은 형식)
     * 조각[0] = 그래프(가중치 뺀 모델), 조각[1...] = 이어 붙이면
     *   [u32 머리 길이][머리 JSON {o: 바깥 머리(base64), e: [[텐서 머리(base64), 행, 열], ...]}][행별 배율 f32...][int8 값...]
     */
    private File modelFile(String name, List<String> files, boolean q8) throws Exception {
        File dir = new File(ctx.getCacheDir(), "tbmodels");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("캐시 폴더를 못 만들었어요");
        long sig = 0;
        for (String f : files) sig = sig * 31 + assetSize(f);
        File out = new File(dir, name + "-" + Long.toHexString(sig) + ".onnx");
        if (out.exists() && out.length() > 0) return out;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().startsWith(name + "-")) //noinspection ResultOfMethodCallIgnored
            f.delete();

        File tmp = new File(dir, out.getName() + ".part");
        if (!q8) {
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                for (String f : files) os.write(readAsset(f));
            }
        } else {
            byte[] graph = readAsset(files.get(0));
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int i = 1; i < files.size(); i++) all.write(readAsset(files.get(i)));
            byte[] blob = all.toByteArray();
            ByteBuffer bl = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            int headLen = bl.getInt(0);
            JSONObject man = new JSONObject(new String(blob, 4, headLen, "UTF-8"));
            byte[] outer = Base64.decode(man.getString("o"), Base64.DEFAULT);
            JSONArray entries = man.getJSONArray("e");
            int totalRows = 0;
            for (int i = 0; i < entries.length(); i++) totalRows += entries.getJSONArray(i).getInt(1);
            int scaleStart = 4 + headLen;
            int qPos = scaleStart + totalRows * 4;
            int rowPos = 0;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20)) {
                os.write(graph);
                os.write(outer);
                for (int i = 0; i < entries.length(); i++) {
                    JSONArray e = entries.getJSONArray(i);
                    byte[] pre = Base64.decode(e.getString(0), Base64.DEFAULT);
                    int rows = e.getInt(1), cols = e.getInt(2);
                    os.write(pre);
                    ByteBuffer fl = ByteBuffer.allocate(cols * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int r = 0; r < rows; r++) {
                        float sc = bl.getFloat(scaleStart + (rowPos + r) * 4);
                        int base = qPos + r * cols;
                        fl.clear();
                        for (int c = 0; c < cols; c++) fl.putFloat(blob[base + c] * sc);
                        os.write(fl.array(), 0, cols * 4);
                    }
                    qPos += rows * cols;
                    rowPos += rows;
                }
            }
        }
        if (!tmp.renameTo(out)) throw new Exception("모델 파일을 저장하지 못했어요");
        return out;
    }
}
