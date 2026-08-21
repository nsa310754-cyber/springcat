/*
 * codeconv アプリアイコン生成スクリプト
 * ------------------------------------
 * SVG をその場で組み立て、Chromium ヘッドレスで各密度の PNG に書き出す。
 * デザイン: SpringCat のオレンジ地 + 変換記号（⇄ 二方向矢印）を白で配置。
 *   （Block Destroy のレンガ流用をやめ、codeconv 専用の独自アイコンにする）
 *
 * 使い方: node build-icons.js /path/to/chrome
 * 出力: ./out/ 以下に各 PNG。呼び出し側 (build-icons.sh) が res/ へ配置する。
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const CHROME = process.argv[2];
const OUT = path.join(__dirname, 'out');
fs.mkdirSync(OUT, { recursive: true });

// 変換記号（⇄）。ローカル座標 0..108。白で塗る。
function emblem(scale, cx, cy) {
  // 基準ボックス中心(51,49.5) を (cx,cy) に合わせて scale 倍で配置
  return `<g transform="translate(${cx},${cy}) scale(${scale}) translate(-51,-49.5)" fill="#ffffff">
    <rect x="16" y="33" width="47" height="11" rx="5.5"/>
    <path d="M58 26 L84 38.5 L58 51 Z"/>
    <rect x="45" y="55" width="47" height="11" rx="5.5"/>
    <path d="M50 48 L24 60.5 L50 73 Z"/>
  </g>`;
}

const GRAD = `<defs>
  <linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#ffa562"/>
    <stop offset="1" stop-color="#e8743b"/>
  </linearGradient>
</defs>`;

// 各バリアントの SVG（viewBox 0 0 108 108）
const VARIANTS = {
  // レガシー（API<26）用の完成アイコン。角丸四角。
  square: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${GRAD}
    <rect x="4" y="4" width="100" height="100" rx="22" fill="url(#g)"/>
    ${emblem(0.62, 54, 54)}
  </svg>`,
  // レガシー丸アイコン。
  round: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${GRAD}
    <circle cx="54" cy="54" r="50" fill="url(#g)"/>
    ${emblem(0.62, 54, 54)}
  </svg>`,
  // アダプティブ背景（フルブリード）。
  bg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${GRAD}
    <rect x="0" y="0" width="108" height="108" fill="url(#g)"/>
  </svg>`,
  // アダプティブ前景（透過。セーフゾーン内に紋章）。
  fg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
    ${emblem(0.55, 54, 54)}
  </svg>`
};

// 密度ごとの出力ピクセル
const LEGACY = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const ADAPT  = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };

function render(svg, px, outPath) {
  const html = `<!doctype html><meta charset="utf-8">
    <style>html,body{margin:0;padding:0;background:transparent}
    svg{display:block;width:${px}px;height:${px}px}</style>${svg}`;
  const htmlPath = path.join(OUT, 'tmp.html');
  fs.writeFileSync(htmlPath, html);
  execFileSync(CHROME, [
    '--headless', '--disable-gpu', '--no-sandbox',
    '--force-device-scale-factor=1',
    '--default-background-color=00000000',
    '--hide-scrollbars',
    `--screenshot=${outPath}`,
    `--window-size=${px},${px}`,
    'file://' + htmlPath
  ], { stdio: 'ignore' });
}

const jobs = [];
for (const [d, px] of Object.entries(LEGACY)) {
  jobs.push(['ic_launcher', d, VARIANTS.square, px]);
  jobs.push(['ic_launcher_round', d, VARIANTS.round, px]);
}
for (const [d, px] of Object.entries(ADAPT)) {
  jobs.push(['ic_launcher_foreground', d, VARIANTS.fg, px]);
  jobs.push(['ic_launcher_background', d, VARIANTS.bg, px]);
}
for (const [name, d, svg, px] of jobs) {
  const dir = path.join(OUT, 'mipmap-' + d);
  fs.mkdirSync(dir, { recursive: true });
  const p = path.join(dir, name + '.png');
  render(svg, px, p);
  console.log('rendered', 'mipmap-' + d + '/' + name + '.png', px + 'px');
}
// ストア用 512px アイコンも出す
render(VARIANTS.square, 512, path.join(OUT, 'playstore-icon-512.png'));
console.log('done');
