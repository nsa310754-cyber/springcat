// 落ちてくるブロックを並べて、横 1 列がそろうと消える落ち物ゲーム (キャンバスに描く)。
// Bionic Runner のゲームモード用サンプル:  /run blocks.js game
//
// 操作: ◀ ▶ 移動 / ▲ か Z で回転 / ▼ 1 段落とす / Space 一気に落とす / P 一時停止 / R やり直し
const W = 10, H = 20, S = 24;
const canvas = document.getElementById('game');
canvas.width = W * S + 130;
canvas.height = H * S;
const g = canvas.getContext('2d');

const SHAPES = [
  [[0, 1], [1, 1], [2, 1], [3, 1]], [[1, 0], [2, 0], [1, 1], [2, 1]],
  [[1, 0], [0, 1], [1, 1], [2, 1]], [[1, 0], [2, 0], [0, 1], [1, 1]],
  [[0, 0], [1, 0], [1, 1], [2, 1]], [[0, 0], [0, 1], [1, 1], [2, 1]],
  [[2, 0], [0, 1], [1, 1], [2, 1]],
];
const COLORS = ['#22d3ee', '#fde047', '#e879f9', '#4ade80', '#f87171', '#60a5fa', '#fb923c'];

let board, cur, next, score, lines, over, paused, fall;

function piece(kind) {
  return { kind, cells: SHAPES[kind].map(c => c.slice()), x: W / 2 - 2, y: 0 };
}

function fits(p) {
  return p.cells.every(([cx, cy]) => {
    const x = p.x + cx, y = p.y + cy;
    return x >= 0 && x < W && y < H && (y < 0 || !board[y][x]);
  });
}

function rotated(p) {
  if (p.kind === 1) return p;
  const n = p.kind === 0 ? 4 : 3;
  return { ...p, cells: p.cells.map(([cx, cy]) => [n - 1 - cy, cx]) };
}

function reset() {
  board = Array.from({ length: H }, () => Array(W).fill(0));
  score = lines = fall = 0;
  over = paused = false;
  cur = piece(Math.floor(Math.random() * 7));
  next = Math.floor(Math.random() * 7);
}

function lock() {
  for (const [cx, cy] of cur.cells) if (cur.y + cy >= 0) board[cur.y + cy][cur.x + cx] = cur.kind + 1;
  const kept = board.filter(row => row.some(v => !v));
  const cleared = H - kept.length;
  while (kept.length < H) kept.unshift(Array(W).fill(0));
  board = kept;
  lines += cleared;
  score += [0, 100, 300, 500, 800][cleared];
  cur = piece(next);
  next = Math.floor(Math.random() * 7);
  if (!fits(cur)) over = true;
}

function move(dx, dy) {
  const q = { ...cur, x: cur.x + dx, y: cur.y + dy };
  if (!fits(q)) return false;
  cur = q;
  return true;
}

function block(x, y, kind) {
  g.fillStyle = COLORS[kind - 1];
  g.fillRect(x + 1, y + 1, S - 2, S - 2);
  g.fillStyle = 'rgba(255,255,255,.25)';
  g.fillRect(x + 1, y + 1, S - 2, 4);
}

function draw() {
  g.fillStyle = '#0b1020';
  g.fillRect(0, 0, canvas.width, canvas.height);
  g.fillStyle = '#141a33';
  g.fillRect(0, 0, W * S, H * S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (board[y][x]) block(x * S, y * S, board[y][x]);
  if (!over) for (const [cx, cy] of cur.cells) block((cur.x + cx) * S, (cur.y + cy) * S, cur.kind + 1);
  g.fillStyle = '#e5e7eb';
  g.font = '15px monospace';
  g.fillText('SCORE', W * S + 14, 28);
  g.fillText(String(score), W * S + 14, 48);
  g.fillText('LINES', W * S + 14, 84);
  g.fillText(String(lines), W * S + 14, 104);
  g.fillText('NEXT', W * S + 14, 140);
  for (const [cx, cy] of SHAPES[next]) block(W * S + 14 + cx * S, 150 + cy * S, next + 1);
  if (over || paused) {
    g.fillStyle = 'rgba(0,0,0,.6)';
    g.fillRect(0, H * S / 2 - 36, W * S, 72);
    g.fillStyle = over ? '#f87171' : '#fde047';
    g.font = 'bold 22px monospace';
    g.textAlign = 'center';
    g.fillText(over ? 'GAME OVER' : 'PAUSED', W * S / 2, H * S / 2);
    g.font = '13px monospace';
    g.fillStyle = '#e5e7eb';
    g.fillText(over ? 'R: retry' : 'P: resume', W * S / 2, H * S / 2 + 22);
    g.textAlign = 'left';
  }
}

document.addEventListener('keydown', e => {
  const k = e.key;
  if (k === 'r' || k === 'R') reset();
  else if (k === 'p' || k === 'P' || k === 'Escape') paused = !paused;
  else if (!over && !paused) {
    if (k === 'ArrowLeft') move(-1, 0);
    else if (k === 'ArrowRight') move(1, 0);
    else if (k === 'ArrowDown') { if (!move(0, 1)) lock(); }
    else if (k === 'ArrowUp' || k === 'z' || k === 'x') {
      // 壁ぎわでは、左右に 1 マスずらして回せるか試す
      for (const dx of [0, -1, 1, -2, 2]) {
        const q = { ...rotated(cur), x: cur.x + dx };
        if (fits(q)) { cur = q; break; }
      }
    } else if (k === ' ') { while (move(0, 1)) score++; lock(); }
  }
  draw();
});

reset();
draw();
setInterval(() => {
  if (over || paused) return;
  // 10 行消すごとに速くなる
  if (++fall >= Math.max(2, 12 - Math.floor(lines / 10))) {
    fall = 0;
    if (!move(0, 1)) lock();
    draw();
  }
}, 50);
