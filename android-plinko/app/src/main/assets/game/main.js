// 3D プリンコ (現金なし)。
// three.min.js は UMD で <script> から THREE を globals にロード済み。
// cannon-es は ESM モジュール。
import * as CANNON from './cannon-es.js';

// ----------------------------------------------------------------------------
// 盤面寸法
// ----------------------------------------------------------------------------
const W = 6;          // 盤の横幅
const H = 11;         // 盤の縦長さ
const DEPTH = 0.6;    // ボール転がる奥行き (薄い箱)
const PEG_ROWS = 10;  // 針の行数
const PEG_COLS = 7;   // 1行の針の数 (互い違いに +1 の時もある)
const PEG_R = 0.11;
const BALL_R = 0.26;
const SLOT_COUNT = 7;
const SLOT_SCORES = [100, 50, 20, 500, 20, 50, 100]; // 中央がジャックポット
const SLOT_COLORS = [0x7a5cff, 0x4ad66d, 0xffd166, 0xff4d6d, 0xffd166, 0x4ad66d, 0x7a5cff];

// ----------------------------------------------------------------------------
// Three.js シーン
// ----------------------------------------------------------------------------
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x101014);
scene.fog = new THREE.Fog(0x101014, 15, 30);

const camera = new THREE.PerspectiveCamera(45, innerWidth / innerHeight, 0.1, 100);
camera.position.set(0, 0.5, 13);
camera.lookAt(0, 0, 0);

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
document.body.appendChild(renderer.domElement);

// ライト
scene.add(new THREE.AmbientLight(0xffffff, 0.45));
const key = new THREE.DirectionalLight(0xffffff, 1.1);
key.position.set(4, 10, 6);
key.castShadow = true;
key.shadow.mapSize.set(1024, 1024);
key.shadow.camera.near = 1; key.shadow.camera.far = 25;
key.shadow.camera.left = -8; key.shadow.camera.right = 8;
key.shadow.camera.top = 10; key.shadow.camera.bottom = -10;
scene.add(key);
const fill = new THREE.DirectionalLight(0xa0b8ff, 0.35);
fill.position.set(-5, 2, 5);
scene.add(fill);

// 盤の背面パネル
const back = new THREE.Mesh(
  new THREE.BoxGeometry(W + 1, H + 1, 0.4),
  new THREE.MeshStandardMaterial({ color: 0x1b1d27, roughness: 0.9, metalness: 0.1 })
);
back.position.set(0, 0, -DEPTH / 2 - 0.2);
back.receiveShadow = true;
scene.add(back);

// 枠フレーム (左右と上下)
function frame(w, h, x, y) {
  const m = new THREE.Mesh(
    new THREE.BoxGeometry(w, h, DEPTH + 0.2),
    new THREE.MeshStandardMaterial({ color: 0x3a4055, roughness: 0.6, metalness: 0.3 })
  );
  m.position.set(x, y, 0);
  m.castShadow = true; m.receiveShadow = true;
  scene.add(m);
}
frame(0.3, H, -W/2 - 0.15, 0);
frame(0.3, H, +W/2 + 0.15, 0);
frame(W + 0.6, 0.3, 0, +H/2 + 0.15);

// ----------------------------------------------------------------------------
// Cannon.js 世界
// ----------------------------------------------------------------------------
const world = new CANNON.World({ gravity: new CANNON.Vec3(0, -14, 0) });
world.broadphase = new CANNON.SAPBroadphase(world);
world.solver.iterations = 20;
world.defaultContactMaterial.restitution = 0.55;
world.defaultContactMaterial.friction = 0.04;

const ballMat = new CANNON.Material('ball');
const pegMat  = new CANNON.Material('peg');
const wallMat = new CANNON.Material('wall');
world.addContactMaterial(new CANNON.ContactMaterial(ballMat, pegMat,
  { restitution: 0.75, friction: 0.02 }));
world.addContactMaterial(new CANNON.ContactMaterial(ballMat, wallMat,
  { restitution: 0.3, friction: 0.08 }));

// 盤を薄い箱と見立てて、前後に不可視の壁 (ボールが Z 方向に飛ばないため)
function invisibleWall(x, y, z, sx, sy, sz) {
  const body = new CANNON.Body({
    mass: 0, material: wallMat,
    shape: new CANNON.Box(new CANNON.Vec3(sx / 2, sy / 2, sz / 2)),
  });
  body.position.set(x, y, z);
  world.addBody(body);
}
invisibleWall(0, 0, -DEPTH/2 - 0.05, W + 1, H + 1, 0.1); // 奥
invisibleWall(0, 0, +DEPTH/2 + 0.05, W + 1, H + 1, 0.1); // 手前
invisibleWall(-W/2 - 0.15, 0, 0, 0.3, H + 1, DEPTH + 0.4); // 左
invisibleWall(+W/2 + 0.15, 0, 0, 0.3, H + 1, DEPTH + 0.4); // 右
invisibleWall(0, +H/2 + 0.15, 0, W + 0.6, 0.3, DEPTH + 0.4); // 天井 (発射部より上)

// ----------------------------------------------------------------------------
// 針 (ペグ) : 円柱で表現。互い違いに配置。
// ----------------------------------------------------------------------------
const pegGeo = new THREE.CylinderGeometry(PEG_R, PEG_R, DEPTH, 16);
const pegMtl = new THREE.MeshStandardMaterial({ color: 0xd0d4e4, roughness: 0.3, metalness: 0.7 });
pegGeo.rotateX(Math.PI / 2); // 円柱の軸を Z 方向 (奥行き方向) に
const pegTop = +H/2 - 1.8;
const pegBot = -H/2 + 2.6;
const spanY = pegTop - pegBot;
const spacingX = W / (PEG_COLS + 1);
for (let r = 0; r < PEG_ROWS; r++) {
  const y = pegTop - (spanY * r / (PEG_ROWS - 1));
  const offset = (r % 2 === 0) ? 0 : spacingX / 2;
  const cols = (r % 2 === 0) ? PEG_COLS : PEG_COLS - 1;
  for (let c = 0; c < cols; c++) {
    const x = (c - (cols - 1) / 2) * spacingX + (r % 2 === 0 ? 0 : 0);
    const mesh = new THREE.Mesh(pegGeo, pegMtl);
    mesh.position.set(x + offset * 0, y, 0);
    mesh.castShadow = true;
    scene.add(mesh);
    const body = new CANNON.Body({
      mass: 0, material: pegMat,
      shape: new CANNON.Cylinder(PEG_R, PEG_R, DEPTH, 16),
    });
    // Cannon の Cylinder は Y 軸向き → Z 方向に向けるため回転
    const q = new CANNON.Quaternion();
    q.setFromEuler(Math.PI / 2, 0, 0);
    body.quaternion.copy(q);
    body.position.set(x + offset * 0, y, 0);
    world.addBody(body);
  }
}

// ----------------------------------------------------------------------------
// 回ってる障害物: 中段に長方形バーが 1 本、ゆっくり自転。
// Kinematic body で手動回転させる (静的だが位置/角速度は与えられる)。
// ----------------------------------------------------------------------------
const barLen = 2.0, barThick = 0.22;
const barGeo = new THREE.BoxGeometry(barLen, barThick, DEPTH);
const barMtl = new THREE.MeshStandardMaterial({ color: 0xff6b6b, roughness: 0.4, metalness: 0.5 });
const barMesh = new THREE.Mesh(barGeo, barMtl);
barMesh.castShadow = true;
scene.add(barMesh);
const barBody = new CANNON.Body({
  mass: 0, type: CANNON.Body.KINEMATIC, material: pegMat,
  shape: new CANNON.Box(new CANNON.Vec3(barLen/2, barThick/2, DEPTH/2)),
});
barBody.position.set(0, 1.0, 0);
world.addBody(barBody);

// もう 1 本、下寄りに逆回転するバー
const bar2 = new THREE.Mesh(
  new THREE.BoxGeometry(1.4, barThick, DEPTH),
  new THREE.MeshStandardMaterial({ color: 0x4ad6d6, roughness: 0.4, metalness: 0.5 })
);
bar2.castShadow = true;
scene.add(bar2);
const bar2Body = new CANNON.Body({
  mass: 0, type: CANNON.Body.KINEMATIC, material: pegMat,
  shape: new CANNON.Box(new CANNON.Vec3(0.7, barThick/2, DEPTH/2)),
});
bar2Body.position.set(0, -1.7, 0);
world.addBody(bar2Body);

// ----------------------------------------------------------------------------
// ゴール: 下部に SLOT_COUNT 個の仕切りで区切った穴
// ----------------------------------------------------------------------------
const slotTop = -H/2 + 2.0;
const slotBot = -H/2;
const slotW = W / SLOT_COUNT;
for (let i = 0; i <= SLOT_COUNT; i++) {
  const x = -W/2 + i * slotW;
  // 細い仕切り (手前にはみ出さないよう DEPTH と同じ厚み)
  const divider = new THREE.Mesh(
    new THREE.BoxGeometry(0.08, slotTop - slotBot, DEPTH),
    new THREE.MeshStandardMaterial({ color: 0x8c93a8, roughness: 0.5, metalness: 0.4 })
  );
  divider.position.set(x, (slotTop + slotBot) / 2, 0);
  divider.castShadow = true;
  scene.add(divider);
  const body = new CANNON.Body({
    mass: 0, material: wallMat,
    shape: new CANNON.Box(new CANNON.Vec3(0.04, (slotTop - slotBot) / 2, DEPTH / 2)),
  });
  body.position.set(x, (slotTop + slotBot) / 2, 0);
  world.addBody(body);
}

// 床は穴にしたいので、ゴール位置ごとにセンサーボディを置き、ボールが落ちたら削除+スコア加算。
// 底板そのものは物理的には存在しないので、ボールは画面外に落ちる → 検出して削除。
const slotLabels = [];
for (let i = 0; i < SLOT_COUNT; i++) {
  const centerX = -W/2 + slotW * (i + 0.5);
  // 色付きの背景タイル (視覚のみ)
  const tile = new THREE.Mesh(
    new THREE.PlaneGeometry(slotW - 0.1, slotTop - slotBot),
    new THREE.MeshStandardMaterial({ color: SLOT_COLORS[i], emissive: SLOT_COLORS[i],
      emissiveIntensity: 0.25, roughness: 0.8 })
  );
  tile.position.set(centerX, (slotTop + slotBot) / 2, -DEPTH/2 - 0.19);
  scene.add(tile);
  // スコア文字をキャンバスからテクスチャで作成
  const canvas = document.createElement('canvas');
  canvas.width = 128; canvas.height = 128;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.font = 'bold 60px sans-serif';
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText(SLOT_SCORES[i], 64, 72);
  const tex = new THREE.CanvasTexture(canvas);
  const label = new THREE.Mesh(
    new THREE.PlaneGeometry(slotW * 0.8, slotW * 0.8),
    new THREE.MeshBasicMaterial({ map: tex, transparent: true })
  );
  label.position.set(centerX, (slotTop + slotBot) / 2 + 0.2, -DEPTH/2 - 0.18);
  scene.add(label);
  slotLabels.push({ x1: -W/2 + slotW * i, x2: -W/2 + slotW * (i + 1), score: SLOT_SCORES[i], tile });
}

// ----------------------------------------------------------------------------
// ボール
// ----------------------------------------------------------------------------
const ballGeo = new THREE.SphereGeometry(BALL_R, 24, 16);
const ballColors = [0xffd166, 0xff6b6b, 0x7a5cff, 0x4ad66d, 0x4ad6d6, 0xff9f1c];
const balls = []; // {mesh, body}

function dropBall(x) {
  const col = ballColors[Math.floor(Math.random() * ballColors.length)];
  const mat = new THREE.MeshStandardMaterial({ color: col, roughness: 0.3, metalness: 0.5,
    emissive: col, emissiveIntensity: 0.08 });
  const mesh = new THREE.Mesh(ballGeo, mat);
  mesh.castShadow = true;
  scene.add(mesh);
  const body = new CANNON.Body({
    mass: 0.1, material: ballMat,
    shape: new CANNON.Sphere(BALL_R),
    linearDamping: 0.08, angularDamping: 0.1,
  });
  body.position.set(
    Math.max(-W/2 + BALL_R + 0.1, Math.min(W/2 - BALL_R - 0.1, x)),
    H/2 - 0.6, 0);
  body.velocity.set((Math.random() - 0.5) * 0.3, 0, 0);
  world.addBody(body);
  balls.push({ mesh, body });
  totalBalls++;
  document.getElementById('balls').textContent = totalBalls;
  // 最大ボール数 (負荷対策)
  if (balls.length > 40) {
    const old = balls.shift();
    scene.remove(old.mesh);
    world.removeBody(old.body);
  }
}

// ----------------------------------------------------------------------------
// 入力: 画面タップ → ボールが落ちる X 座標を決定
// ----------------------------------------------------------------------------
const raycaster = new THREE.Raycaster();
const plane = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0); // z=0 平面
function tapToWorldX(clientX, clientY) {
  const ndc = new THREE.Vector2(
    (clientX / innerWidth) * 2 - 1,
    -(clientY / innerHeight) * 2 + 1);
  raycaster.setFromCamera(ndc, camera);
  const hit = new THREE.Vector3();
  raycaster.ray.intersectPlane(plane, hit);
  return hit.x;
}
renderer.domElement.addEventListener('pointerdown', (e) => {
  const x = tapToWorldX(e.clientX, e.clientY);
  dropBall(x);
});

// リセット
document.getElementById('reset').addEventListener('click', () => {
  for (const b of balls) { scene.remove(b.mesh); world.removeBody(b.body); }
  balls.length = 0;
  score = 0; totalBalls = 0;
  updateHud();
});

let score = 0, totalBalls = 0;
function updateHud() {
  document.getElementById('score').textContent = score;
  document.getElementById('balls').textContent = totalBalls;
}

function showLast(n, color) {
  const el = document.getElementById('last');
  el.textContent = '+' + n;
  el.style.color = '#' + color.toString(16).padStart(6, '0');
  el.style.opacity = '1';
  clearTimeout(showLast._t);
  showLast._t = setTimeout(() => el.style.opacity = '0', 700);
}

// ----------------------------------------------------------------------------
// ループ
// ----------------------------------------------------------------------------
let t0 = performance.now();
function tick(now) {
  const dt = Math.min(0.033, (now - t0) / 1000);
  t0 = now;

  // 回転障害物: 一定角速度で回す
  const w1 = 1.2, w2 = -1.7;
  const q1 = new CANNON.Quaternion();
  q1.setFromAxisAngle(new CANNON.Vec3(0, 0, 1), (now / 1000) * w1);
  barBody.quaternion.copy(q1);
  const q2 = new CANNON.Quaternion();
  q2.setFromAxisAngle(new CANNON.Vec3(0, 0, 1), (now / 1000) * w2);
  bar2Body.quaternion.copy(q2);

  world.step(1 / 60, dt, 3);

  barMesh.position.copy(barBody.position);
  barMesh.quaternion.copy(barBody.quaternion);
  bar2.position.copy(bar2Body.position);
  bar2.quaternion.copy(bar2Body.quaternion);

  // ボール位置同期 + 落下検出
  for (let i = balls.length - 1; i >= 0; i--) {
    const b = balls[i];
    b.mesh.position.copy(b.body.position);
    b.mesh.quaternion.copy(b.body.quaternion);
    if (b.body.position.y < slotBot + 0.1) {
      // どのスロットに入ったか
      for (const s of slotLabels) {
        if (b.body.position.x >= s.x1 && b.body.position.x <= s.x2) {
          score += s.score;
          showLast(s.score, s.tile.material.color.getHex());
          // タイルを光らせる
          const base = s.tile.material.emissiveIntensity;
          s.tile.material.emissiveIntensity = 1.2;
          setTimeout(() => s.tile.material.emissiveIntensity = base, 180);
          updateHud();
          break;
        }
      }
      scene.remove(b.mesh);
      world.removeBody(b.body);
      balls.splice(i, 1);
    } else if (b.body.position.y < -H) {
      scene.remove(b.mesh);
      world.removeBody(b.body);
      balls.splice(i, 1);
    }
  }

  renderer.render(scene, camera);
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});
