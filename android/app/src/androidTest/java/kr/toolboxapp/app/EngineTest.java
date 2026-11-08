package kr.toolboxapp.app;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kr.toolboxapp.tbai.TbEngine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 기기 AI 엔진 자동 시험 (GitHub 클라우드의 안드로이드 에뮬레이터에서 돌아요).
 * 앱 안에 들어간 모델 파일을 실제로 풀고(8비트 → 원래 모델) 돌려서 결과 모양·값을 확인해요.
 */
@RunWith(AndroidJUnit4.class)
public class EngineTest {
    private static final String TAG = "TbAITest";

    private Context ctx() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static float[] fill(int n, float v) { float[] a = new float[n]; Arrays.fill(a, v); return a; }

    private Map<String, TbEngine.Tensor> one(String name, long[] shape, float v) {
        int n = 1; for (long d : shape) n *= (int) d;
        Map<String, TbEngine.Tensor> m = new HashMap<>();
        m.put(name, new TbEngine.Tensor(shape, fill(n, v)));
        return m;
    }

    @Test
    public void fastBackgroundModel() throws Exception {
        TbEngine e = new TbEngine(ctx());
        TbEngine.Loaded l = e.load("bg", Arrays.asList("models/u2netp.wasm"), false, false);
        long t = System.currentTimeMillis();
        Map<String, TbEngine.Tensor> out = e.run("bg", one(l.inputs.get(0), new long[]{1, 3, 320, 320}, 0.1f));
        Log.i(TAG, "u2netp " + l.accel + " " + (System.currentTimeMillis() - t) + "ms");
        TbEngine.Tensor o = out.get(l.outputs.get(0));
        assertEquals(320 * 320, o.data.length);
    }

    @Test
    public void hqBackgroundModelQ8() throws Exception {
        TbEngine e = new TbEngine(ctx());
        List<String> files = new ArrayList<>();
        files.add("models/isnet-g.wasm");
        for (int i = 1; i <= 9; i++) files.add("models/isnet-" + i + ".wasm");
        TbEngine.Loaded l = e.load("bgHQ", files, true, false);
        long t = System.currentTimeMillis();
        Map<String, TbEngine.Tensor> out = e.run("bgHQ", one(l.inputs.get(0), new long[]{1, 3, 1024, 1024}, 0f));
        Log.i(TAG, "isnet " + l.accel + " " + (System.currentTimeMillis() - t) + "ms");
        TbEngine.Tensor o = out.get(l.outputs.get(0));
        assertEquals(1024 * 1024, o.data.length);
        boolean finite = true; for (float f : o.data) if (Float.isNaN(f)) { finite = false; break; }
        assertTrue(finite);
    }

    @Test
    public void eraseModelQ8() throws Exception {
        TbEngine e = new TbEngine(ctx());
        TbEngine.Loaded l = e.load("erase", Arrays.asList("models/migan-g.wasm", "models/migan-1.wasm", "models/migan-2.wasm"), true, false);
        long t = System.currentTimeMillis();
        Map<String, TbEngine.Tensor> out = e.run("erase", one(l.inputs.get(0), new long[]{1, 4, 512, 512}, 0.5f));
        Log.i(TAG, "migan " + l.accel + " " + (System.currentTimeMillis() - t) + "ms");
        assertEquals(3 * 512 * 512, out.get(l.outputs.get(0)).data.length);
    }

    /** 새 누끼 모델(BiRefNet) — DeformConv 연산이 이 엔진에서 되는지가 핵심 */
    @Test
    public void proBackgroundModel() throws Exception {
        TbEngine e = new TbEngine(ctx());
        List<String> files = new ArrayList<>();
        files.add("models/bir-g.wasm");
        for (int i = 1; i <= 10; i++) files.add("models/bir-" + i + ".wasm");
        TbEngine.Loaded l = e.load("bgPro", files, true, false);
        long t = System.currentTimeMillis();
        Map<String, TbEngine.Tensor> out = e.run("bgPro", one(l.inputs.get(0), new long[]{1, 3, 1024, 1024}, 0f));
        Log.i(TAG, "birefnet " + l.accel + " " + (System.currentTimeMillis() - t) + "ms");
        TbEngine.Tensor o = out.get(l.outputs.get(0));
        assertEquals(1024 * 1024, o.data.length);
        for (int i = 0; i < o.data.length; i += 4096) assertTrue(o.data[i] >= 0f && o.data[i] <= 1f);
    }
}
