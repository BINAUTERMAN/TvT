import fs from 'node:fs/promises';
import { chromium } from 'playwright';

// 大屏媒体面板使用高分辨率像素源，避免系统放大矢量图的小尺寸缓存。
const output = new URL(
  '../android/res/drawable-sw600dp-nodpi/',
  import.meta.url,
);
const browser = await chromium.launch({
  executablePath:
    process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
});
try {
  await fs.mkdir(output, { recursive: true });
  const page = await browser.newPage();
  for (const name of [
    'ic_heart',
    'ic_heart_filled',
    'ic_lyrics',
    'ic_lyrics_active',
    'ic_lyrics_unlock',
  ]) {
    const xml = await fs.readFile(
      new URL(`../android/res/drawable/${name}.xml`, import.meta.url),
      'utf8',
    );
    const paths = [...xml.matchAll(/<path\s+([^>]+)\/>/g)]
      .map(([, source]) => {
        const attributes = Object.fromEntries(
          [...source.matchAll(/android:(\w+)="([^"]*)"/g)].map(
            ([, key, value]) => [key, value],
          ),
        );
        const color = (value) => {
          if (!value || value === '@android:color/transparent') return 'none';
          return /^#[0-9a-f]{8}$/i.test(value)
            ? `#${value.slice(3)}${value.slice(1, 3)}`
            : value;
        };
        return `<path d="${attributes.pathData}" fill="${color(attributes.fillColor)}" stroke="${color(attributes.strokeColor)}" stroke-width="${attributes.strokeWidth ?? 0}" stroke-linecap="${attributes.strokeLineCap ?? 'butt'}" stroke-linejoin="${attributes.strokeLineJoin ?? 'miter'}"/>`;
      })
      .join('');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="384" height="384" viewBox="0 0 44 44"><g transform="translate(10 10)">${paths}</g></svg>`;
    const png = await page.evaluate(async (source) => {
      const picture = new Image();
      picture.src = `data:image/svg+xml;base64,${btoa(source)}`;
      await picture.decode();
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 384;
      canvas.getContext('2d').drawImage(picture, 0, 0);
      return canvas.toDataURL('image/png').split(',')[1];
    }, svg);
    await fs.writeFile(
      new URL(`${name}.png`, output),
      Buffer.from(png, 'base64'),
    );
  }
} finally {
  await browser.close();
}
