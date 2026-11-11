"""압축(8비트) 모델이 원본(32비트)과 같은 결과를 내는지 숫자로 확인해요.
실제로 배포하는 조각 파일(app/web/models/*.wasm)을 풀어서 비교하므로, '검증한 파일 = 나가는 파일'이에요.
쓰는 법:  python3 -I quality/q8_check.py            (전체)
          python3 -I quality/q8_check.py lama gfp   (일부만)
통과 기준을 넘으면 종료 코드 1 — 새 모델을 넣거나 압축 방식을 바꾼 뒤 반드시 돌려요.
(2026-11: LaMa를 처음 압축했을 때 배치정규화 값까지 8비트로 줄여 색이 틀어졌는데, 이 검사가 있었다면 바로 잡혔어요.)"""
import sys, os, json, base64, struct, glob
import numpy as np, onnxruntime as ort
from PIL import Image
S = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = S + '/app/web/models/'
IMGS = [S + '/nk/img/' + n for n in ('girl-1.jpg', 'animal-1.jpg', 'car-1.jpg')]
so = ort.SessionOptions(); so.intra_op_num_threads = 2; so.log_severity_level = 3

def unpack(prefix, n):
    """웹의 q8Model()과 같은 방식으로 조각을 풀어 ONNX 바이트로 되돌려요"""
    g = open(WEB + prefix + '-g.wasm', 'rb').read()
    blob = b''.join(open(WEB + f'{prefix}-{i}.wasm', 'rb').read() for i in range(1, n + 1))
    ml = struct.unpack('<I', blob[:4])[0]; man = json.loads(blob[4:4 + ml].decode()); off = 4 + ml
    rows = sum(e[1] for e in man['e']); scales = np.frombuffer(blob, np.float32, rows, off); off += rows * 4
    out = bytearray(g) + base64.b64decode(man['o']); si = 0
    for pre, r, c in man['e']:
        q = np.frombuffer(blob, np.int8, r * c, off).reshape(r, c); off += r * c
        out += base64.b64decode(pre) + (q * scales[si:si + r, None]).astype(np.float32).tobytes(); si += r
    assert off == len(blob), 'blob size mismatch'
    return bytes(out)

def img(path, size):
    return np.asarray(Image.open(path).convert('RGB').resize((size, size), Image.LANCZOS)).astype(np.float32)

def feeds(kind, path):
    if kind == 'lama':
        x = img(path, 512) / 255; m = np.zeros((1, 1, 512, 512), np.float32); m[..., 150:330, 180:380] = 1
        return {'image': (x.transpose(2, 0, 1)[None] * (1 - m)).astype(np.float32), 'mask': m}, m[0, 0] > 0
    if kind == 'migan':
        x = img(path, 512) / 127.5 - 1; m = np.zeros((512, 512), np.float32); m[150:330, 180:380] = 1
        return np.concatenate([(0.5 - m)[None], x.transpose(2, 0, 1) * (1 - m)])[None].astype(np.float32), m > 0
    if kind == 'gfp': return (img(path, 512) / 255).transpose(2, 0, 1)[None].astype(np.float32), None
    if kind == 'isnet': return (img(path, 1024) / 255 - 0.5).transpose(2, 0, 1)[None].astype(np.float32), None
    if kind == 'esrw': return (img(path, 128) / 255).transpose(2, 0, 1)[None].astype(np.float32), None   # 화질 개선 고화질 모델: 128 → 512
    if kind == 'bir': return ((img(path, 1024) / 255 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32), None

MODELS = {   # 이름: (원본 경로, 조각 접두어, 조각 수, 종류, 기준)
    'lama':  (S + '/mdl/lama512.onnx', 'lama', 11, 'fill', dict(mean=0.02)),
    'gfp':   (S + '/mdl/gfp/gfpgan14.onnx', 'gfp', 18, 'image', dict(mean=0.006)),
    'migan': (S + '/q8pk/migan.onnx', 'migan', 2, 'fill', dict(mean=0.03)),
    'isnet': (S + '/mdl/isnet.onnx', 'isnet', 9, 'mask', dict(mean=0.01, iou=0.985)),
    'bir':   (S + '/mdl/bir_dc.onnx', 'bir', 10, 'mask', dict(mean=0.01, iou=0.985)),
    'esrw':  (S + '/sr/onnx/esrw_fp32.onnx', 'esrw', 4, 'image', dict(mean=0.006)),
}
def run(name):
    ref_path, prefix, n, kind, lim = MODELS[name]
    if not os.path.exists(ref_path): return dict(model=name, skip='원본 파일 없음')
    # 메모리를 아끼려고 원본을 먼저 다 돌리고 내린 뒤, 압축본을 올려요
    fds = [feeds(name, p) for p in IMGS]; outs = []
    for src in (ref_path, None):
        sess = ort.InferenceSession(src or unpack(prefix, n), so, providers=['CPUExecutionProvider'])
        outs.append([sess.run(None, f if isinstance(f, dict) else {sess.get_inputs()[0].name: f})[0].astype(np.float32) for f, _ in fds]); del sess
    means, maxs, ious = [], [], []
    for (f, hole), a, b in zip(fds, outs[0], outs[1]):
        if kind == 'mask':
            def norm(o):
                o = o.reshape(-1); lo, hi = o.min(), o.max(); return (o - lo) / ((hi - lo) or 1) if name == 'isnet' else np.clip(o, 0, 1)
            a, b = norm(a), norm(b); ia, ib = a > .5, b > .5; ious.append(float((ia & ib).sum() / max(1, (ia | ib).sum())))
            d = np.abs(a - b)
        else:
            if name == 'migan': a, b = (a + 1) / 2, (b + 1) / 2
            d = np.abs(np.clip(a, 0, 1) - np.clip(b, 0, 1))
            if hole is not None: d = d[0][:, hole]
        means.append(float(d.mean())); maxs.append(float(d.max()))
    r = dict(model=name, mean=round(max(means), 5), max=round(max(maxs), 4), iou=round(min(ious), 4) if ious else None, limit=lim)
    r['ok'] = r['mean'] <= lim['mean'] and (not ious or r['iou'] >= lim['iou'])
    return r

if __name__ == '__main__':
    names = sys.argv[1:] or list(MODELS); res = []
    for n in names:
        try: r = run(n)
        except Exception as e: r = dict(model=n, ok=False, error=str(e)[:200])
        res.append(r); print(('OK  ' if r.get('ok') else 'SKIP' if r.get('skip') else 'FAIL'), json.dumps(r, ensure_ascii=False), flush=True)
    f = S + '/quality/q8_result.json'; old = {}
    try: old = {r['model']: r for r in json.load(open(f))}
    except Exception: pass
    old.update({r['model']: r for r in res}); json.dump(list(old.values()), open(f, 'w'), ensure_ascii=False, indent=1)   # 일부만 돌려도 나머지 결과는 남겨요
    sys.exit(0 if all(r.get('ok') or r.get('skip') for r in res) else 1)
