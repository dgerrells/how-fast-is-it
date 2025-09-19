console.log("worker created");
const isMobile = /Mobi/.test(navigator.userAgent);
let lastSetupEvent = null;
let activeGrid = null;
// I don't make trash
let cacher = {
  x: 0,
  y: 0,
};
const fixedForce = isMobile ? 258300 * 8 : 2583000 * 15;

const simulate = (event) => {
  const {
    sabViewParticles,
    sabViewSimData,
    id,
    particleOffsetStart,
    particleOffsetEnd,
    particleStride,
    particleGridA,
    particleGridB,
  } = event.data;
  activeGrid = activeGrid === particleGridA ? particleGridB : particleGridA;

  const [delta, width, height, touchCount] = [
    sabViewSimData[0],
    sabViewSimData[1],
    sabViewSimData[2],
    sabViewSimData[3],
    sabViewSimData[4],
    sabViewSimData[5],
  ];

  const start = particleOffsetStart;
  const end = particleOffsetEnd;
  const decay = 1 / (1 + delta * 1);
  for (let i = start; i < end; i++) {
    const pi = i * particleStride;
    let x = sabViewParticles[pi];
    let y = sabViewParticles[pi + 1];
    let dx = sabViewParticles[pi + 2] * decay;
    let dy = sabViewParticles[pi + 3] * decay;

    if (touchCount > 0) {
      for (let t = 0; t < touchCount; t++) {
        const tx = sabViewSimData[4 + t * 2];
        const ty = sabViewSimData[4 + t * 2 + 1];
        forceInvSqr(tx, ty, x, y, fixedForce);
        dx += cacher.x * delta * 3;
        dy += cacher.y * delta * 3;
      }
    }

    x += dx * delta;
    y += dy * delta;

    if (x > width) x = 0;
    if (x < 0) x = width;
    if (y > height) y = 0;
    if (y < 0) y = height;

    sabViewParticles[pi] = x;
    sabViewParticles[pi + 1] = y;
    sabViewParticles[pi + 2] = dx;
    sabViewParticles[pi + 3] = dy;


    // if (x < 0 || x >= width) continue;
    // if (y < 0 || y >= height) continue;
    const pCountIndex = (y | 0) * width + (x | 0);
    activeGrid[pCountIndex] = clamp(activeGrid[pCountIndex] + 1);
    // activeGrid[pCountIndex]++;
  }

  postMessage({});
};

function clamp(n) {
  n &= -(n >= 0);
  return n | ((255 - n) >> 31);
}

function forceInvSqr(x1, y1, x2, y2, m = 25830000) {
  const dx = x1 - x2;
  const dy = y1 - y2;
  const dist = Math.sqrt(dx * dx + dy * dy);
  const dirX = dx / dist;
  const dirY = dy / dist;
  const force = Math.min(1200, m / (dist * dist));
  cacher.x = force * dirX;
  cacher.y = force * dirY;
}

function forceInvCube(x1, y1, x2, y2, m = 25830000) {
  const dx = x1 - x2;
  const dy = y1 - y2;
  const dist = Math.sqrt(dx * dx + dy * dy);
  const dirX = dx / dist;
  const dirY = dy / dist;
  const force = Math.min(12000, m / (dist * dist * dist));
  cacher.x = force * dirX;
  cacher.y = force * dirY;
}

function forceSqr(x1, y1, x2, y2, d = 999999) {
  const dx = x1 - x2;
  const dy = y1 - y2;
  const dist = Math.sqrt(dx * dx + dy * dy);
  if (d <= dist) {
    const dirX = dx / dist;
    const dirY = dy / dist;
    const force = Math.min(12000, dist * dist);
    cacher.x = force * dirX;
    cacher.y = force * dirY;
    return;
  }
  cacher.x = 0;
  cacher.y = 0;
}

onmessage = (event) => {
  if (!event.data.sabViewParticles) {
    simulate(lastSetupEvent);
    return;
  }

  lastSetupEvent = event;
  postMessage({});
};
