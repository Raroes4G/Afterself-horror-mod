// Writes the default soul model and placeholder animations as GeckoLib files, ready to open in Blockbench.
// Same layout and UVs as a player, so any 64x64 skin fits. Usage: node generate-model.js <assets/afterself folder> [--force]
// It never overwrites work done in Blockbench: an existing model file is left alone, and in the animation file only
// the animations that are missing are added. --force rewrites everything from scratch.
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
const force = process.argv.includes('--force');

// Cube helper: origin in model space, size, UV of the base layer and of the outer (hat/sleeve) layer.
function cube(origin, size, uv, inflate) {
  const c = { origin, size, uv };
  if (inflate) c.inflate = inflate;
  return c;
}

function bones(armWidth) {
  const armX = 4 + armWidth;
  return [
    { name: 'root', pivot: [0, 0, 0] },
    { name: 'body', parent: 'root', pivot: [0, 24, 0], cubes: [
      cube([-4, 12, -2], [8, 12, 4], [16, 16]),
      cube([-4, 12, -2], [8, 12, 4], [16, 32], 0.25),
    ] },
    { name: 'head', parent: 'body', pivot: [0, 24, 0], cubes: [
      cube([-4, 24, -4], [8, 8, 8], [0, 0]),
      cube([-4, 24, -4], [8, 8, 8], [32, 0], 0.5),
    ] },
    { name: 'rightArm', parent: 'body', pivot: [-5, 22, 0], cubes: [
      cube([-armX, 12, -2], [armWidth, 12, 4], [40, 16]),
      cube([-armX, 12, -2], [armWidth, 12, 4], [40, 32], 0.25),
    ] },
    { name: 'rightItem', parent: 'rightArm', pivot: [-6, 12, -1] },
    { name: 'leftArm', parent: 'body', pivot: [5, 22, 0], cubes: [
      cube([4, 12, -2], [armWidth, 12, 4], [32, 48]),
      cube([4, 12, -2], [armWidth, 12, 4], [48, 48], 0.25),
    ] },
    { name: 'leftItem', parent: 'leftArm', pivot: [6, 12, -1] },
    { name: 'rightLeg', parent: 'root', pivot: [-1.9, 12, 0], cubes: [
      cube([-3.9, 0, -2], [4, 12, 4], [0, 16]),
      cube([-3.9, 0, -2], [4, 12, 4], [0, 32], 0.25),
    ] },
    { name: 'leftLeg', parent: 'root', pivot: [1.9, 12, 0], cubes: [
      cube([-0.1, 0, -2], [4, 12, 4], [16, 48]),
      cube([-0.1, 0, -2], [4, 12, 4], [0, 48], 0.25),
    ] },
  ];
}

function geo(identifier, armWidth) {
  return {
    format_version: '1.12.0',
    'minecraft:geometry': [{
      description: {
        identifier: `geometry.${identifier}`,
        texture_width: 64,
        texture_height: 64,
        visible_bounds_width: 3,
        visible_bounds_height: 3,
        visible_bounds_offset: [0, 1.5, 0],
      },
      bones: bones(armWidth),
    }],
  };
}

// GeckoLib's Molang parser rejects a minus right after an operator ("* -18"): negatives are written as 0 - x.
const signed = (expression, amount) => amount < 0 ? `0 - ${expression} * ${-amount}` : `${expression} * ${amount}`;
const swing = (bone, amount, speed) => ({ rotation: [signed(`math.cos(query.anim_time * ${speed})`, amount), 0, 0] });

// One pose for scenes that hold still: x, y, z rotation per bone.
const pose = (entries) => {
  const out = {};
  for (const [bone, value] of Object.entries(entries)) {
    out[bone] = value;
  }
  return out;
};

const animations = {
  idle: { loop: true, animation_length: 4, bones: {
    rightArm: { rotation: [0, 0, 'math.sin(query.anim_time * 90) * 2 + 2'] },
    leftArm: { rotation: [0, 0, '0 - math.sin(query.anim_time * 90) * 2 - 2'] },
  } },
  walk: { loop: true, animation_length: 1, bones: {
    rightLeg: swing('rightLeg', 30, 360), leftLeg: swing('leftLeg', -30, 360),
    rightArm: swing('rightArm', -25, 360), leftArm: swing('leftArm', 25, 360),
  } },
  sprint: { loop: true, animation_length: 0.6, bones: {
    body: { rotation: [8, 0, 0] },
    rightLeg: swing('rightLeg', 50, 600), leftLeg: swing('leftLeg', -50, 600),
    rightArm: swing('rightArm', -50, 600), leftArm: swing('leftArm', 50, 600),
  } },
  crouch: { loop: true, animation_length: 1, bones: pose({
    body: { rotation: [28, 0, 0], position: [0, -3, 0] },
    head: { rotation: [-28, 0, 0] },
    rightLeg: { position: [0, 0, -3] }, leftLeg: { position: [0, 0, -3] },
  }) },
  crouch_walk: { loop: true, animation_length: 1.4, bones: {
    body: { rotation: [28, 0, 0], position: [0, -3, 0] },
    head: { rotation: [-28, 0, 0] },
    rightLeg: { position: [0, 0, -3], rotation: ['math.cos(query.anim_time * 257) * 18', 0, 0] },
    leftLeg: { position: [0, 0, -3], rotation: ['0 - math.cos(query.anim_time * 257) * 18', 0, 0] },
  } },
  attack: { loop: false, animation_length: 0.3, bones: {
    rightArm: { rotation: { '0.0': [0, 0, 0], '0.12': [-110, 0, 10], '0.3': [0, 0, 0] } },
  } },
  // The rare scenes. Redraw these in Blockbench under the same names: no code needed.
  lure_wave: { loop: true, animation_length: 1.5, bones: {
    rightArm: { rotation: [0, 0, '150 + math.sin(query.anim_time * 480) * 20'] },
    head: { rotation: [0, 'math.sin(query.anim_time * 120) * 6', 0] },
  } },
  lure_kneel: { loop: true, animation_length: 3, bones: {
    root: { position: [0, -6, 0] },
    body: { rotation: ['20 + math.sin(query.anim_time * 240) * 2', 0, 0] },
    head: { rotation: ['35 + math.sin(query.anim_time * 240) * 3', 0, 0] },
    rightLeg: { rotation: [-90, 0, 0] }, leftLeg: { rotation: [-90, 0, 0] },
    rightArm: { rotation: [-130, 20, 0] }, leftArm: { rotation: [-130, -20, 0] },
  } },
  lure_lie: { loop: true, animation_length: 6, bones: {
    root: { rotation: [-90, 0, 0], position: [0, 2, 12] },
    head: { rotation: [0, 'math.sin(query.anim_time * 30) * 4', 0] },
    leftArm: { rotation: [0, 0, -35] },
  } },
  lure_stare: { loop: true, animation_length: 5, bones: {
    head: { rotation: [8, 0, 'math.sin(query.anim_time * 72) * 14'] },
    rightArm: { rotation: [0, 0, 3] }, leftArm: { rotation: [0, 0, -3] },
  } },
  // "Come here": the arm held out, the hand calling.
  lure_beckon: { loop: true, animation_length: 1, bones: {
    rightArm: { rotation: ['0 - 80 - math.sin(query.anim_time * 360) * 25', 0, 0] },
    head: { rotation: [0, 0, 6] },
  } },
  // Sitting on the ground, face in its hands, shaking.
  lure_cry: { loop: true, animation_length: 2, bones: {
    root: { position: [0, -10, 0] },
    body: { rotation: ['25 + math.sin(query.anim_time * 1440) * 1.5', 0, 0] },
    head: { rotation: ['40 + math.sin(query.anim_time * 1440) * 2', 0, 0] },
    rightArm: { rotation: [-150, 25, 0] }, leftArm: { rotation: [-150, -25, 0] },
    rightLeg: { rotation: [-80, 10, 0] }, leftLeg: { rotation: [-80, -10, 0] },
  } },
  // Standing with its back turned (the code turns it away), head low, swaying.
  lure_back: { loop: true, animation_length: 9, bones: {
    body: { rotation: [4, 0, 'math.sin(query.anim_time * 40) * 2'] },
    head: { rotation: [18, 0, 'math.sin(query.anim_time * 40) * 3'] },
  } },
  // Short poses outside the lures.
  // Caught watching from afar: straight up, head tilted, staring back.
  watch: { loop: true, animation_length: 6, bones: {
    head: { rotation: [0, 0, '18 + math.sin(query.anim_time * 60) * 4'] },
    rightArm: { rotation: [0, 0, 4] }, leftArm: { rotation: [0, 0, -4] },
  } },
  // Right behind the player, leaning over them.
  loom: { loop: true, animation_length: 7.2, bones: {
    body: { rotation: [18, 0, 0] },
    head: { rotation: [-10, 0, 'math.sin(query.anim_time * 50) * 6'] },
    rightArm: { rotation: [-25, 0, 6] }, leftArm: { rotation: [-25, 0, -6] },
  } },
  // Where it last saw someone: the head sweeps the room.
  search: { loop: true, animation_length: 4, bones: {
    body: { rotation: [0, 'math.sin(query.anim_time * 90) * 12', 0] },
    head: { rotation: [5, 'math.sin(query.anim_time * 90) * 55', 0] },
  } },
  // Played over the others while the shield is up: the left arm comes up and in, the way a player's does.
  guard: { loop: true, animation_length: 1, bones: {
    leftArm: { rotation: [-54, 30, 0] },
  } },
  eat: { loop: true, animation_length: 1, bones: {
    rightArm: { rotation: ['0 - 95 - math.sin(query.anim_time * 1080) * 8', -25, 0] },
    head: { rotation: [10, 0, 0] },
  } },
};

function write(relative, value) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
  console.log('wrote', relative);
}

function writeIfMissing(relative, value) {
  if (!force && fs.existsSync(path.join(root, relative))) {
    console.log('kept', relative);
    return;
  }
  write(relative, value);
}

writeIfMissing('geo/entity/soul.geo.json', geo('soul', 4));
writeIfMissing('geo/entity/soul_slim.geo.json', geo('soul_slim', 3));

const animationFile = 'animations/entity/soul.animation.json';
const animationPath = path.join(root, animationFile);
if (!force && fs.existsSync(animationPath)) {
  const existing = JSON.parse(fs.readFileSync(animationPath, 'utf8'));
  existing.animations = existing.animations || {};
  const added = [];
  for (const [name, animation] of Object.entries(animations)) {
    if (!(name in existing.animations)) {
      existing.animations[name] = animation;
      added.push(name);
    }
  }
  if (added.length > 0) {
    fs.writeFileSync(animationPath, JSON.stringify(existing, null, 2) + '\n');
  }
  console.log(added.length > 0 ? 'added animations: ' + added.join(', ') : 'kept ' + animationFile);
} else {
  write(animationFile, { format_version: '1.8.0', animations });
}
