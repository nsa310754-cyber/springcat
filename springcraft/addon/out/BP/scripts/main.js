import { world, system } from "@minecraft/server";

/**
 * SpringCat Addon — Spring Turbo Block
 *
 * springcat:turbo_block を敷いた上にレール(通常/パワード/アクティベーター
 * どれでも可)を置くと、その上を通過するトロッコを「毎tick」加速し続ける。
 * バニラのパワードレールは加速目標が 8 block/s でハードキャップされているが、
 * これはエンジン内蔵のレール加速ロジックの上限であり、Script API の
 * applyImpulse で外から与える速度はこの上限の対象外のため、無上限に加速する。
 *
 * 安全のための注意 (README 参照):
 *  - 本当に上限を設けていないため、長い直線ではいずれ 1tick で1ブロック以上
 *    移動するような速度域に達し、当たり判定がすり抜けたり脱線しうる。
 *  - 減速したい場合は turbo_block を敷かない区間を挟めばよい(バニラの
 *    摩擦で自然に減速する)。
 */

const TURBO_BLOCK = "springcat:turbo_block";

// 1tickごとに進行方向へ加算する速度 (block/tick)。20tick=1秒。
const BOOST_PER_TICK = 0.12;
// これ未満の速度では加速しない(静止したトロッコが誤って動き出さないように)。
const MIN_SPEED_TO_BOOST = 0.02;

const MINECART_TYPES = [
  "minecraft:minecart",
  "minecraft:chest_minecart",
  "minecraft:hopper_minecart",
  "minecraft:tnt_minecart",
  "minecraft:command_block_minecart",
];

system.runInterval(() => {
  for (const dimensionId of ["overworld", "nether", "the_end"]) {
    let dimension;
    try {
      dimension = world.getDimension(dimensionId);
    } catch (e) {
      continue;
    }
    for (const type of MINECART_TYPES) {
      let carts;
      try {
        carts = dimension.getEntities({ type });
      } catch (e) {
        continue;
      }
      for (const cart of carts) {
        boostIfOnTurboBlock(dimension, cart);
      }
    }
  }
}, 1);

function boostIfOnTurboBlock(dimension, cart) {
  let velocity;
  try {
    velocity = cart.getVelocity();
  } catch (e) {
    return; // カートが既に無効化されている等
  }

  const horizSpeed = Math.hypot(velocity.x, velocity.z);
  if (horizSpeed < MIN_SPEED_TO_BOOST) return;

  let supportBlock;
  try {
    // レール自体は非ソリッドなので、getBlockBelow はレールを透過して
    // その下の最初のソリッドブロック (= turbo_block) を返す。
    supportBlock = dimension.getBlockBelow(cart.location);
  } catch (e) {
    return;
  }
  if (!supportBlock || supportBlock.typeId !== TURBO_BLOCK) return;

  const dirX = velocity.x / horizSpeed;
  const dirZ = velocity.z / horizSpeed;

  try {
    cart.applyImpulse({ x: dirX * BOOST_PER_TICK, y: 0, z: dirZ * BOOST_PER_TICK });
  } catch (e) {
    // カートが同tick中に破棄された等
  }
}
