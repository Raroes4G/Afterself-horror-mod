// Synthesises the six screamer sounds and encodes them as OGG Vorbis with ffmpeg (must be on the PATH).
// Usage: node generate-screamer.js <assets/afterself folder> [--keep-wav]
// No sample is used: everything is computed here, so the sounds belong to the mod. The same seed gives the same file.
// Each sound is cut to fit one screamer of ScreamerOverlay: what is heard before the hit follows what is seen before
// the lunge, and the hit falls on the lunge itself.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const root = process.argv[2];
const keepWav = process.argv.includes('--keep-wav');
const SR = 44100;

let seed = 0;
function random() {
  seed = (seed + 0x6d2b79f5) >>> 0;
  let x = seed;
  x = Math.imul(x ^ (x >>> 15), x | 1);
  x ^= x + Math.imul(x ^ (x >>> 7), x | 61);
  return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
}
const noise = () => random() * 2 - 1;

// A resonant band-pass (RBJ cookbook), one instance per use.
function bandpass(freq, q) {
  const w = (2 * Math.PI * freq) / SR;
  const alpha = Math.sin(w) / (2 * q);
  const b0 = alpha, b2 = -alpha, a0 = 1 + alpha, a1 = -2 * Math.cos(w), a2 = 1 - alpha;
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  return (x) => {
    const y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0;
    x2 = x1; x1 = x; y2 = y1; y1 = y;
    return y;
  };
}
function lowpass(freq) {
  const k = 1 - Math.exp((-2 * Math.PI * freq) / SR);
  let y = 0;
  return (x) => (y += k * (x - y));
}
const saw = (phase) => 2 * (phase - Math.floor(phase + 0.5));
const clamp01 = (x) => Math.max(0, Math.min(1, x));
const TAU = 2 * Math.PI;

// ---------------------------------------------------------------------------------------------------------------
// The first scream, kept exactly as it was first made: a breath in, a low hit, clashing voices tearing upward, and
// a dark tail. Do not reorder anything in here: the random numbers are drawn in this order.
// ---------------------------------------------------------------------------------------------------------------
function classic() {
  const LENGTH = 2.4;
  const HIT = 0.19;
  const N = Math.floor(SR * LENGTH);
  seed = 0x5117ce;
  const out = new Float64Array(N);
  {
    const band = bandpass(1400, 0.8);
    let phase = 0;
    for (let i = 0; i < Math.floor(HIT * SR); i++) {
      const t = i / SR;
      const rise = Math.pow(t / HIT, 3);
      phase += (180 + 1100 * rise) / SR;
      out[i] += band(noise()) * rise * 0.9 + Math.sin(2 * Math.PI * phase) * rise * 0.08;
    }
  }
  {
    let phase = 0;
    const crack = bandpass(3200, 0.6);
    for (let i = Math.floor(HIT * SR); i < N; i++) {
      const t = i / SR - HIT;
      phase += (34 + 95 * Math.exp(-t * 16)) / SR;
      out[i] += Math.sin(2 * Math.PI * phase) * Math.exp(-t * 4.2) * 1.1;
      out[i] += crack(noise()) * Math.exp(-t * 70) * 1.6;
    }
  }
  {
    const start = HIT + 0.005;
    const hold = 1.25;
    const release = 0.4;
    const formants = [bandpass(950, 5), bandpass(1750, 6), bandpass(3100, 7)];
    const hiss = bandpass(4300, 1.2);
    const jitter = lowpass(11);
    const ratios = [1, 1.0595, 1.4142, 0.5, 2.003];
    const gains = [1, 0.9, 0.55, 0.5, 0.3];
    const phases = ratios.map(() => random());
    for (let i = Math.floor(start * SR); i < N; i++) {
      const t = i / SR - start;
      if (t > hold + release) {
        break;
      }
      const envelope = clamp01(t / 0.012) * (t < hold ? 1 : Math.pow(1 - (t - hold) / release, 2));
      const glide = 430 + 520 * (1 - Math.exp(-t * 14)) - 130 * clamp01((t - 0.3) / 0.6) + 260 * clamp01((t - 0.95) / 0.25);
      const wobble = 1 + 0.045 * Math.sin(2 * Math.PI * 8.3 * t) + jitter(noise()) * 2.2;
      const freq = glide * wobble;
      let voice = 0;
      for (let v = 0; v < ratios.length; v++) {
        phases[v] += (freq * ratios[v]) / SR;
        voice += saw(phases[v]) * gains[v];
      }
      const rasp = (0.55 + 0.45 * Math.sign(Math.sin(2 * Math.PI * 71 * t))) * (0.8 + 0.2 * Math.sin(2 * Math.PI * 29 * t));
      let shaped = voice * 0.25;
      for (const formant of formants) {
        shaped += formant(voice) * 0.9;
      }
      shaped = Math.tanh(shaped * rasp * 1.9);
      out[i] += shaped * envelope * 0.95 + hiss(noise()) * envelope * 0.2;
    }
  }
  {
    const start = HIT + 0.9;
    for (let i = Math.floor(start * SR); i < N; i++) {
      const t = i / SR - start;
      const level = clamp01(t / 0.3) * Math.exp(-t * 1.9) * 0.5;
      out[i] += (Math.sin(2 * Math.PI * 55 * t) + Math.sin(2 * Math.PI * 58.4 * t)) * level;
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Building blocks for the other screams. All times are in seconds from the start of the file.
// ---------------------------------------------------------------------------------------------------------------
function span(out, from, to, each) {
  const last = Math.min(out.length, Math.floor(to * SR));
  for (let i = Math.max(0, Math.floor(from * SR)); i < last; i++) {
    out[i] += each(i / SR - from, i);
  }
}

/** The sub drop and the crack that land with the picture. */
function hit(out, at, gain) {
  let phase = 0;
  const crack = bandpass(3200, 0.6);
  span(out, at, out.length / SR, (t) => {
    phase += (34 + 95 * Math.exp(-t * 16)) / SR;
    return (Math.sin(TAU * phase) * Math.exp(-t * 4.2) * 1.1 + crack(noise()) * Math.exp(-t * 70) * 1.6) * gain;
  });
}

/** Noise swelling out of nothing and stopping dead. */
function breathIn(out, from, to, gain) {
  const band = bandpass(1400, 0.8);
  let phase = 0;
  span(out, from, to, (t) => {
    const rise = Math.pow(t / (to - from), 3);
    phase += (180 + 1100 * rise) / SR;
    return (band(noise()) * rise * 0.9 + Math.sin(TAU * phase) * rise * 0.08) * gain;
  });
}

/** One heavy step on a wooden floor. */
function thud(out, at, gain) {
  let phase = 0;
  const knock = bandpass(420, 1.4);
  span(out, at, at + 0.35, (t) => {
    phase += (42 + 60 * Math.exp(-t * 30)) / SR;
    return (Math.sin(TAU * phase) * Math.exp(-t * 17) * 1.2 + knock(noise()) * Math.exp(-t * 55) * 0.8) * gain;
  });
}

/** Someone breathing words too quietly to make out. */
function whisper(out, from, to, gain) {
  const mouth = bandpass(2300, 1.6);
  const teeth = bandpass(5200, 2.5);
  const length = to - from;
  span(out, from, to, (t) => {
    const edge = Math.min(1, t / 0.08, (length - t) / 0.03);
    const syllable = Math.pow(0.5 + 0.5 * Math.sin(TAU * 4.6 * t + 1.3), 2);
    const sample = noise();
    return (mouth(sample) * 0.8 + teeth(sample) * 0.5 * syllable) * (0.25 + 0.75 * syllable) * edge * gain;
  });
}

/** A very short click. */
function tick(out, at, gain) {
  const band = bandpass(3800, 2);
  span(out, at, at + 0.03, (t) => band(noise()) * Math.exp(-t * 220) * gain);
}

/** A sharp little breath: one more of them has appeared. */
function gasp(out, at, gain, freq) {
  const band = bandpass(freq, 3);
  span(out, at, at + 0.09, (t) => band(noise()) * Math.min(1, t / 0.008) * Math.exp(-t * 38) * gain);
}

/** A heart: two low beats. */
function heartbeat(out, at, gain) {
  for (const [offset, level] of [[0, 1], [0.15, 0.75]]) {
    let phase = 0;
    span(out, at + offset, at + offset + 0.25, (t) => {
      phase += (48 + 30 * Math.exp(-t * 40)) / SR;
      return Math.sin(TAU * phase) * Math.min(1, t / 0.004) * Math.exp(-t * 24) * level * gain;
    });
  }
}

/** Two low notes beating against each other. Fades in, then dies away on its own or stops dead. */
function drone(out, from, to, low, high, gain, fadeIn, decay) {
  span(out, from, to, (t) => {
    const level = clamp01(t / fadeIn) * (decay > 0 ? Math.exp(-t * decay) : 1);
    return (Math.sin(TAU * low * t) + Math.sin(TAU * high * t)) * level * gain;
  });
}

/** A thin note that climbs, like a ringing in the ears. */
function ringing(out, from, to, lowHz, highHz, gain) {
  let phase = 0;
  const length = to - from;
  span(out, from, to, (t) => {
    const progress = t / length;
    phase += (lowHz + (highHz - lowHz) * progress) / SR;
    return Math.sin(TAU * phase) * progress * progress * gain;
  });
}

/**
 * A screaming voice. glide(t) gives its pitch in Hz, t counted from its own start. The voices in "ratios" sing at
 * that pitch times each ratio: a semitone or a tritone apart is what keeps it from ever sounding in tune.
 */
function scream(out, o) {
  const formants = o.formants.map(([freq, q]) => bandpass(freq, q));
  const hiss = bandpass(o.hissHz || 4300, 1.2);
  const jitter = lowpass(11);
  const phases = o.ratios.map(() => random());
  span(out, o.start, o.start + o.hold + o.release, (t) => {
    const envelope = clamp01(t / (o.attack || 0.012)) * (t < o.hold ? 1 : Math.pow(1 - (t - o.hold) / o.release, 2));
    const freq = o.glide(t) * (1 + o.vibratoDepth * Math.sin(TAU * o.vibratoHz * t) + jitter(noise()) * o.jitter);
    let voice = 0;
    for (let v = 0; v < o.ratios.length; v++) {
      phases[v] += (freq * o.ratios[v]) / SR;
      voice += saw(phases[v]) * o.gains[v];
    }
    const rasp = (0.55 + 0.45 * Math.sign(Math.sin(TAU * o.raspA * t))) * (0.8 + 0.2 * Math.sin(TAU * o.raspB * t));
    let shaped = voice * 0.25;
    for (const formant of formants) {
      shaped += formant(voice) * 0.9;
    }
    shaped = Math.tanh(shaped * rasp * 1.9);
    return (shaped * envelope * 0.95 + hiss(noise()) * envelope * o.hiss) * o.gain;
  });
}

const CLASH = [1, 1.0595, 1.4142, 0.5, 2.003];
const CLASH_GAINS = [1, 0.9, 0.55, 0.5, 0.3];
const THROAT = [[950, 5], [1750, 6], [3100, 7]];
const rise = (t, rate) => 1 - Math.exp(-t * rate);

// Footsteps: two steps coming closer in the dark, then it is there, and a second voice joins above the first.
function steps() {
  seed = 0x57e95;
  const out = new Float64Array(Math.floor(SR * 2.3));
  thud(out, 0.09, 0.75);
  thud(out, 0.32, 1.05);
  hit(out, 0.49, 1);
  scream(out, { start: 0.495, hold: 0.95, release: 0.45, gain: 1, hiss: 0.2,
    glide: (t) => 520 + 640 * rise(t, 16) - 120 * clamp01((t - 0.3) / 0.5) + 300 * clamp01((t - 0.75) / 0.2),
    ratios: CLASH, gains: CLASH_GAINS, formants: THROAT, vibratoHz: 8.9, vibratoDepth: 0.05, jitter: 2.2, raspA: 71, raspB: 29 });
  scream(out, { start: 0.67, hold: 0.7, release: 0.5, gain: 0.45, hiss: 0.1,
    glide: (t) => 1280 + 260 * rise(t, 9) + 90 * Math.sin(TAU * 1.3 * t),
    ratios: [1, 1.0595, 1.498], gains: [1, 0.8, 0.4], formants: [[1500, 5], [2700, 6], [3900, 7]],
    vibratoHz: 10.5, vibratoDepth: 0.06, jitter: 2.6, raspA: 97, raspB: 41 });
  drone(out, 1.35, 2.3, 55, 58.4, 0.5, 0.3, 1.9);
  return out;
}

// Eyes: whispering while the two lights come closer, a click when they blink, nothing at all, then the highest
// scream of them all.
function eyes() {
  seed = 0xe7e5;
  const out = new Float64Array(Math.floor(SR * 2.6));
  whisper(out, 0.06, 0.56, 0.75);
  tick(out, 0.33, 0.6);
  hit(out, 0.62, 1);
  scream(out, { start: 0.625, hold: 1.05, release: 0.6, gain: 1, hiss: 0.24,
    glide: (t) => 760 + 820 * rise(t, 18) + 60 * Math.sin(TAU * 0.9 * t),
    ratios: [1, 1.0595, 1.498, 2.0, 2.52], gains: [1, 0.85, 0.5, 0.45, 0.3], formants: [[1300, 5], [2400, 6], [3600, 7]],
    vibratoHz: 11, vibratoDepth: 0.06, jitter: 2.8, raspA: 83, raspB: 37 });
  drone(out, 1.6, 2.6, 55, 58.4, 0.45, 0.3, 1.9);
  return out;
}

// Falling: it drops on you from above, and the scream falls with it, down into a growl.
function fall() {
  seed = 0xfa11;
  const out = new Float64Array(Math.floor(SR * 2.5));
  breathIn(out, 0.06, 0.25, 0.7);
  hit(out, 0.25, 1.05);
  scream(out, { start: 0.255, hold: 1.2, release: 0.5, gain: 1, hiss: 0.2,
    glide: (t) => 380 + 1250 * Math.exp(-t * 2.2),
    ratios: [1, 1.0595, 1.4142, 0.5, 0.25], gains: [1, 0.9, 0.5, 0.7, 0.45], formants: THROAT,
    vibratoHz: 6.5, vibratoDepth: 0.05, jitter: 2.4, raspA: 61, raspB: 23 });
  scream(out, { start: 0.85, hold: 0.9, release: 0.6, gain: 0.7, hiss: 0.05, attack: 0.25,
    glide: (t) => 96 - 24 * clamp01(t / 1.2),
    ratios: [1, 1.5, 2.01, 3.02], gains: [1, 0.7, 0.6, 0.4], formants: [[420, 3], [900, 4], [1600, 5]],
    vibratoHz: 5, vibratoDepth: 0.03, jitter: 3, raspA: 41, raspB: 17 });
  return out;
}

// Crowd: a small breath each time another face appears, then all of them at once, each on its own note.
function crowd() {
  seed = 0xc20d;
  const out = new Float64Array(Math.floor(SR * 2.7));
  for (let face = 0; face < 9; face++) {
    gasp(out, 0.1 + face * 0.07, 0.45 + face * 0.08, 1500 + face * 190);
  }
  hit(out, 0.76, 1.15);
  const notes = [340, 455, 610, 815, 1090];
  const delays = [0, 0.03, 0.06, 0.02, 0.08];
  for (let v = 0; v < notes.length; v++) {
    const note = notes[v];
    scream(out, { start: 0.765 + delays[v], hold: 0.9 + v * 0.05, release: 0.5, gain: 0.5, hiss: 0.1,
      glide: (t) => note * (0.8 + 0.3 * rise(t, 12)) * (1 + 0.12 * clamp01((t - 0.7) / 0.3)),
      ratios: [1, 1.0595, 1.4142, 2.003], gains: [1, 0.8, 0.5, 0.3],
      formants: [[900 + v * 110, 5], [1700 + v * 150, 6], [3000 + v * 120, 7]],
      vibratoHz: 7 + v * 0.8, vibratoDepth: 0.05, jitter: 2.4, raspA: 59 + v * 9, raspB: 23 + v * 5 });
  }
  drone(out, 1.7, 2.7, 55, 58.4, 0.5, 0.3, 1.9);
  return out;
}

// Slow: a hum, a heart beating harder, a ringing that climbs, and it all stops dead a breath before the scream,
// which is short and cut off.
function slow() {
  seed = 0x5108;
  const out = new Float64Array(Math.floor(SR * 2.7));
  drone(out, 0.05, 1.185, 48, 50.6, 0.42, 0.6, 0);
  heartbeat(out, 0.3, 0.55);
  heartbeat(out, 0.76, 0.95);
  ringing(out, 0.2, 1.185, 5200, 6500, 0.13);
  hit(out, 1.2, 1.2);
  scream(out, { start: 1.205, hold: 0.5, release: 0.22, gain: 1, hiss: 0.22,
    glide: (t) => 850 + 650 * rise(t, 22),
    ratios: CLASH, gains: CLASH_GAINS, formants: [[1100, 5], [2000, 6], [3300, 7]],
    vibratoHz: 9.5, vibratoDepth: 0.05, jitter: 2.6, raspA: 79, raspB: 31 });
  drone(out, 1.95, 2.7, 55, 58.4, 0.28, 0.25, 2.2);
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// A small hall around all of it (four combs and two all-passes), so a scream does not stop like a switch.
// ---------------------------------------------------------------------------------------------------------------
function reverb(input, mix) {
  const combs = [1557, 1617, 1491, 1422].map((size) => ({ buffer: new Float64Array(size), index: 0, damp: 0 }));
  const passes = [225, 556].map((size) => ({ buffer: new Float64Array(size), index: 0 }));
  const result = new Float64Array(input.length);
  for (let i = 0; i < input.length; i++) {
    let wet = 0;
    for (const comb of combs) {
      const delayed = comb.buffer[comb.index];
      comb.damp = delayed * 0.72 + comb.damp * 0.28;
      comb.buffer[comb.index] = input[i] * 0.2 + comb.damp * 0.83;
      comb.index = (comb.index + 1) % comb.buffer.length;
      wet += delayed;
    }
    for (const pass of passes) {
      const delayed = pass.buffer[pass.index];
      pass.buffer[pass.index] = wet + delayed * 0.5;
      pass.index = (pass.index + 1) % pass.buffer.length;
      wet = delayed - wet * 0.5;
    }
    result[i] = input[i] * (1 - mix) + wet * mix;
  }
  return result;
}

/** Reverb, a loud but never clipped level, a few milliseconds of fade at each end, then the OGG file. */
function finish(name, out) {
  const N = out.length;
  const wet = reverb(out, 0.3);
  let peak = 0;
  for (let i = 0; i < N; i++) {
    wet[i] = Math.tanh(wet[i] * 1.15);
    peak = Math.max(peak, Math.abs(wet[i]));
  }
  const fade = Math.floor(0.004 * SR);
  const tail = Math.floor(0.25 * SR);
  const wav = Buffer.alloc(44 + N * 2);
  wav.write('RIFF', 0);
  wav.writeUInt32LE(36 + N * 2, 4);
  wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(SR, 24);
  wav.writeUInt32LE(SR * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write('data', 36);
  wav.writeUInt32LE(N * 2, 40);
  for (let i = 0; i < N; i++) {
    const edge = Math.min(1, i / fade, (N - 1 - i) / tail);
    wav.writeInt16LE(Math.round((wet[i] / peak) * 0.97 * edge * 32767), 44 + i * 2);
  }
  const target = path.join(root, 'sounds', name + '.ogg');
  const temporary = path.join(os.tmpdir(), 'afterself-' + name + '.wav');
  fs.writeFileSync(temporary, wav);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', temporary, '-c:a', 'libvorbis', '-q:a', '6', '-ac', '1', target]);
  if (keepWav) {
    console.log('wav', temporary);
  } else {
    fs.unlinkSync(temporary);
  }
  // A quick look at the shape, in tenths of a second.
  const steps = [];
  for (let s = 0; s < Math.ceil((N / SR) * 10); s++) {
    let sum = 0;
    let count = 0;
    for (let i = Math.floor((s * SR) / 10); i < Math.min(N, Math.floor(((s + 1) * SR) / 10)); i++) {
      sum += (wet[i] / peak) * (wet[i] / peak);
      count++;
    }
    steps.push(Math.sqrt(sum / count).toFixed(2));
  }
  console.log(name.padEnd(15), fs.statSync(target).size, 'bytes |', steps.join(' '));
}

// The file names are the sound names in sounds.json and in ScreamerOverlay.
finish('screamer', classic());
finish('screamer_steps', steps());
finish('screamer_eyes', eyes());
finish('screamer_fall', fall());
finish('screamer_crowd', crowd());
finish('screamer_slow', slow());
