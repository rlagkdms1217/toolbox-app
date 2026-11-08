// 웹(app/web) → 앱(www) 복사. 웹에서 고친 게 그대로 앱에 들어가요.
import fs from 'fs'; import path from 'path';
const here = path.dirname(new URL(import.meta.url).pathname);
const SRC = process.env.WEB_SRC || path.resolve(here, '../../webpack2/web'), DST = path.resolve(here, '../www');
const skip = [/^test\.html$/, /^_/, /\.bak/, /^sw\.js$/, /^admin/, /^lib\/ort130\//, /^lib\/ort-wasm-simd\.jsep\.wasm$/, /^lib\/ort\.webgpu/];   // 테스트·관리자·서비스워커는 앱에 안 넣어요
if (!fs.existsSync(SRC)) { console.log('웹 폴더가 없어서 www 를 그대로 둬요 (웹 zip의 web 폴더 내용을 www 에 넣어 두면 돼요)'); process.exit(0); }
fs.rmSync(DST, {recursive: true, force: true});
let n = 0, bytes = 0;
(function walk(rel) {
  for (const e of fs.readdirSync(path.join(SRC, rel), {withFileTypes: true})) {
    const r = rel ? rel + '/' + e.name : e.name;
    if (skip.some(re => re.test(r))) continue;
    if (e.isDirectory()) { walk(r); continue; }
    fs.mkdirSync(path.join(DST, rel), {recursive: true}); fs.copyFileSync(path.join(SRC, r), path.join(DST, r)); n++; bytes += fs.statSync(path.join(DST, r)).size;
  }
})('');
console.log(`www: ${n} files, ${(bytes / 1e6).toFixed(1)} MB`);
