/*
 * AmiJa website - pixel art.
 *
 * Every image on the desktop is a grid of characters drawn into a canvas at
 * the current scale, so it stays sharp at any zoom. Gadgets, the floppy disk
 * and the mouse pointer are copied pixel for pixel from a Workbench 1.3
 * screen. Icons and gadgets are hires: one character is one column wide and
 * one line tall. The pointer is a sprite: one character is two columns wide.
 *
 *   .  transparent    B  blue    W  white    K  black    O  orange
 *   k  r  L           the three pointer colours
 */
"use strict";

const WB = (window.WB = window.WB || {});

WB.PALETTE = {
  B: "#0055AD",
  W: "#FFFFFF",
  K: "#000021",
  O: "#FF8A00",
  k: "#000000",
  r: "#DE2021",
  L: "#ADBACE",
};

/* Workbench 1.3 highlights a selected icon by complementing its colours:
   the whole box, background included. */
WB.COMPLEMENT = { ".": "O", B: "O", W: "K", K: "W", O: "B" };

WB.ART = {
  amija: [
    "...............................OOOOKKOOOO...",
    ".............................OOOOKKOOOO.....",
    "...........................OOOOKKOOOO.......",
    ".......OO................OOOOKKOOOO.........",
    ".......OOOO............OOOOKKOOOO...........",
    ".......KKOOOO........OOOOKKOOOO.............",
    ".......OOKKOOOO....OOOOKKOOOO...............",
    ".......OOOOKKOOOOOOOOKKOOOO.................",
    ".........OOOOKKOOOOKKOOOO...................",
    "...........OOOOKKKKOOOO.....................",
    ".............OOOOOOOO.......................",
    "...............OOOO.........................",
    "............................................",
    ".........KKKKKKKKKKKKKKKKKKKKKKKK...........",
    ".........KKWWWWWWWWWWWWWWWWWWWWKKKKKK.......",
    ".........KKWWWWWWWWWWWWWWWWWWWWKK...KK......",
    ".........KKWWWWWWWWWWWWWWWWWWWWKK..KKK......",
    ".........KKKWWWWWWWWWWWWWWWWWWKKKKKK........",
    "...........KKKWWWWWWWWWWWWWWKKKK............",
    ".............KKKKKKKKKKKKKKKKK..............",
    "....KKKWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWKKK....",
    ".....KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK....",
  ],
  screenshots: [
    ".KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK.",
    ".KKWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWKK.",
    ".KKWWWKKKKKKKKKKKKKKKKKKKKKKKKKKKKWWWKK.",
    ".KKWWWK...................OOOO...KWWWKK.",
    ".KKWWWK..................OOOOO...KWWWKK.",
    ".KKWWWK..................OOOOO...KWWWKK.",
    ".KKWWWK.......WW.................KWWWKK.",
    ".KKWWWK.....WWWWWW.....WW........KWWWKK.",
    ".KKWWWK....WWWWWWWW..WWWWWW......KWWWKK.",
    ".KKWWWK...WWWWWWWWWWWWWWWWWWW....KWWWKK.",
    ".KKWWWK.WWWWWWWWWWWWWWWWWWWWWWW..KWWWKK.",
    ".KKWWWKWWWWWWWWWWWWWWWWWWWWWWWWWWKWWWKK.",
    ".KKWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWKK.",
    ".KKWWWWWWWWWWWWWWWWWWWWWWWWWWWOOOWWWWKK.",
    ".KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK.",
    "...............KKKKKKKKKK...............",
    "...............KKKKKKKKKK...............",
    ".........KKKKKKKKKKKKKKKKKKKKKK.........",
    ".........KKKKKKKKKKKKKKKKKKKKKK.........",
  ],
  video: [
    "...................................KKK..",
    "...........................KKKKKKKKKKK..",
    "...................KKKKKKKKKWWWWKKKKWK..",
    "...........KKKKKKKWWWWKKKKWWWWKKKKKKK...",
    "..KKKKKKKKWWKKKKWWWWKKKKKKKKK...........",
    "..KKKKWWWWKKKKKKKKKKK...................",
    "..KKKWKKKKKKK...........................",
    "..KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK..",
    "..KKWWWWKKKKWWWWKKKKWWWWKKKKWWWWKKKKWK..",
    "..KKKWWWWKKKKWWWWKKKKWWWWKKKKWWWWKKKKK..",
    "..KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK..",
    "..KKWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKKWWWWWWWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKOOKKWWWWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKOOOOOKKWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKOOOOOOOOKWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKOOOOOKKWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKOOKKWWWWWWWWWWWWWWWKK..",
    "..KKWWWWWWWWWWWWKKWWWWWWWWWWWWWWWWWWKK..",
    "..KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK..",
  ],
  download: [
    "KKKKKKKKKOOOOOOOOOOOOOOOOOKKKKKKK..",
    "KKKKKKKKKOOOOOOOOOOKKKKOOOKKKKKKKK.",
    "KKKKKKKKKOOOOOOOOOOKKKKOOOKKKKKKKKK",
    "KKKKKKKKKOOOOOOOOOOKKKKOOOKKKKKKKKK",
    "KKKKKKKKKOOOOOOOOOOOOOOOOOKKKKKKKKK",
    "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK",
    "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK",
    "KKKKKKWWWWWWWWWWWWWWWWWWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWKKKKKKKWWWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWKKOOOKKWWWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWKKOOOKKWWWWWWWWKKKKKK",
    "KKKKKKWWWKKKKKKKOOOKKKKKKKWWWKKKKKK",
    "KKKKKKWWWWKKOOOOOOOOOOOKKWWWWKKKKKK",
    "KKKKKKWWWWWWKKOOOOOOOKKWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWKKOOOKKWWWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWWWKOKWWWWWWWWWWKKKKKK",
    "KK..KKWWWWWWWWWWWKWWWWWWWWWWWKKKKKK",
    "KKKKKKWWWWWWWWWWWWWWWWWWWWWWWKKKKKK",
  ],
  github: [
    ".............KKKKKKKKKKKKK............",
    ".........KKWWWWWWWWWWWWWWWWKK.........",
    "......KKWWWWWWWWWWWWWWWWWWWWWWKK......",
    ".....KWWWWWKKWWWWWWWWWWWWKKWWWWWKK....",
    "...KKWWWWWWKKKKKKKKKKKKKKKKKWWWWWWK...",
    "..KWWWWWWWWKKKKKKKKKKKKKKKKKWWWWWWWK..",
    ".KKWWWWWWWWKKKKKKKKKKKKKKKKKWWWWWWWWK.",
    ".KWWWWWWWWKKKKKKKKKKKKKKKKKKWWWWWWWWK.",
    ".KWWWWWWWWKKKKKKKKKKKKKKKKKKWWWWWWWWWK",
    "KKWWWWWWWWKKKKKKKKKKKKKKKKKKWWWWWWWWWK",
    "KKWWWWWWWWWKKKKKKKKKKKKKKKKWWWWWWWWWWK",
    ".KWWWWWWKKWWKKKKKKKKKKKKKKWWWWWWWWWWK.",
    ".KWWWWWWWKKKKKKKKKKKKKKKWWWWWWWWWWWWK.",
    "..KWWWWWWWWWKKKKKKKKKKKWWWWWWWWWWWWK..",
    "...KWWWWWWWWWWWKKKKKKKKWWWWWWWWWWWK...",
    "....KKWWWWWWWWWKKKKKKKKWWWWWWWWWKK....",
    "......KKWWWWWWWKKKKKKKKWWWWWWWWK......",
    "........KKWWWWWKKKKKKKKWWWWWKK........",
    "............KKKKKKKKKKKKKK............",
  ],
  pointer: [
    "kkkkkk.....",
    "kLLLLLk....",
    "krrrrLk....",
    "krrrLk.....",
    "krrrrLk....",
    "krrkrrLk...",
    ".kk.krrLk..",
    ".....krrLk.",
    "......krrLk",
    ".......krk.",
    "........k..",
  ],
  busy: [
    ".......kkkkk.........",
    ".....kkWWWWWkk.......",
    "....kWWWWWWWWWk.kkk..",
    "..kkWWWWWWWWWWkkWWWk.",
    ".kWWWrrrrWWWWWWWWWWk.",
    "kWWWWWWWrWWWWWWWWWWWk",
    "kWWWWWWrWWWWrrrWWWWWk",
    "kWWWWWrWWWWWWrWWWWWk.",
    ".kWWWrrrrWWWrrrWWWk..",
    "..kkWWWWWWWWWWWWkk...",
    "....kkkkWWWWWkkk.....",
    "........kkkkk........",
  ],
  close: [
    "WWWWWWWWWWWWWWWWWWWW",
    "WWBBBBBBBBBBBBBBBBWW",
    "WWBBWWWWWWWWWWWWBBWW",
    "WWBBWWWWWWWWWWWWBBWW",
    "WWBBWWWWKKKKWWWWBBWW",
    "WWBBWWWWKKKKWWWWBBWW",
    "WWBBWWWWWWWWWWWWBBWW",
    "WWBBWWWWWWWWWWWWBBWW",
    "WWBBBBBBBBBBBBBBBBWW",
    "WWWWWWWWWWWWWWWWWWWW",
  ],
  depthFront: [
    "WWWWWWWWWWWWWWWWWWWWWW",
    "WWBBBBBBBBBBBBBBWWWWWW",
    "WWBBWWWWWWWWWWBBWWWWWW",
    "WWBBWWKKKKKKKKKKKKKKWW",
    "WWBBWWKKKKKKKKKKKKKKWW",
    "WWBBWWKKKKKKKKKKKKKKWW",
    "WWBBBBKKKKKKKKKKKKKKWW",
    "WWWWWWKKKKKKKKKKKKKKWW",
    "WWWWWWKKKKKKKKKKKKKKWW",
    "WWWWWWWWWWWWWWWWWWWWWW",
  ],
  depthBack: [
    "WWWWWWWWWWWWWWWWWWWWWW",
    "WWKKKKKKKKKKKKKKWWWWWW",
    "WWKKKKBBBBBBBBBBBBBBWW",
    "WWKKKKBBWWWWWWWWWWBBWW",
    "WWKKKKBBWWWWWWWWWWBBWW",
    "WWKKKKBBWWWWWWWWWWBBWW",
    "WWKKKKBBWWWWWWWWWWBBWW",
    "WWWWWWBBWWWWWWWWWWBBWW",
    "WWWWWWBBBBBBBBBBBBBBWW",
    "WWWWWWWWWWWWWWWWWWWWWW",
  ],
  up: [
    "WWWWWBBBBBBWWWWW",
    "WWWBBWWBBWWBBWWW",
    "WBBWWWWBBWWWWBBW",
    "WWWWWWWBBWWWWWWW",
    "WWWWWWWBBWWWWWWW",
    "WWWWWWWBBWWWWWWW",
    "WWWWWWWWWWWWWWWW",
  ],
  down: [
    "WWWWWWWWWWWWWWWW",
    "WWWWWWWBBWWWWWWW",
    "WWWWWWWBBWWWWWWW",
    "WWWWWWWBBWWWWWWW",
    "WBBWWWWBBWWWWBBW",
    "WWWBBWWBBWWBBWWW",
    "WWWWWBBBBBBWWWWW",
  ],
  left: [
    "WWWWWWWWWWWWWWWWW",
    "WWWWWWBBBWWWWWWBB",
    "WWWWBBBWWWWWWWWBB",
    "WWBBBWWWWWWWWWWBB",
    "WWBBBBBBBBBBBWWBB",
    "WWBBBWWWWWWWWWWBB",
    "WWWWBBBWWWWWWWWBB",
    "WWWWWWBBBWWWWWWBB",
    "WWWWWWWWWWWWWWWWW",
  ],
  right: [
    "WWWWWWWWWWWWWWWWW",
    "BBWWWWWWBBBWWWWWW",
    "BBWWWWWWWWBBBWWWW",
    "BBWWWWWWWWWWBBBWW",
    "BBWWBBBBBBBBBBBWW",
    "BBWWWWWWWWWWBBBWW",
    "BBWWWWWWWWBBBWWWW",
    "BBWWWWWWBBBWWWWWW",
    "WWWWWWWWWWWWWWWWW",
  ],
  size: [
    "WWWWWWWWWWWWWWWW",
    "WWBBBBBBWWWWWWWW",
    "WWBBWWBBWWWWWWWW",
    "WWBBBBBBBBBBBBWW",
    "WWWWWWBBWWWWBBWW",
    "WWWWWWBBWWWWBBWW",
    "WWWWWWBBWWWWBBWW",
    "WWWWWWBBBBBBBBWW",
    "WWWWWWWWWWWWWWWW",
  ],
};

/* Draws art into a canvas. pw/ph is the size of one character in CSS px;
   edges are rounded to device pixels so no seams appear at odd zoom levels. */
WB.drawArt = function (canvas, rows, pw, ph, map, dprOverride) {
  const dpr = dprOverride || window.devicePixelRatio || 1;
  const w = Math.max(...rows.map((r) => r.length));
  const h = rows.length;
  canvas.width = Math.round(w * pw * dpr);
  canvas.height = Math.round(h * ph * dpr);
  canvas.style.width = w * pw + "px";
  canvas.style.height = h * ph + "px";
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  for (let y = 0; y < h; y++) {
    const y0 = Math.round(y * ph * dpr);
    const y1 = Math.round((y + 1) * ph * dpr);
    for (let x = 0; x < w; x++) {
      let c = rows[y][x] || ".";
      if (map && map[c] !== undefined) c = map[c];
      const col = WB.PALETTE[c];
      if (!col) continue;
      const x0 = Math.round(x * pw * dpr);
      const x1 = Math.round((x + 1) * pw * dpr);
      ctx.fillStyle = col;
      ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
    }
  }
  return canvas;
};

WB.artCanvas = function (rows, pw, ph, map, dprOverride) {
  return WB.drawArt(document.createElement("canvas"), rows, pw, ph, map, dprOverride);
};
