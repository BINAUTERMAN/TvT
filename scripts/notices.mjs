import fs from 'node:fs';
import path from 'node:path';
const lock = JSON.parse(fs.readFileSync('package-lock.json', 'utf8'));
const directories = Object.entries(lock.packages)
  .filter(([location, info]) => location && !info.dev)
  .map(([location]) => location);
const notices = [];
for (const directory of directories) {
  const manifestPath = path.join(directory, 'package.json');
  if (!fs.existsSync(manifestPath)) continue;
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const license = fs
    .readdirSync(directory)
    .find((file) => /^licen[cs]e(?:\.|$)/i.test(file));
  notices.push(
    `${manifest.name} ${manifest.version} (${manifest.license ?? 'see source'})\n${license ? fs.readFileSync(path.join(directory, license), 'utf8') : (manifest.repository?.url ?? '')}`,
  );
}
fs.mkdirSync('web/public/licenses', { recursive: true });
fs.writeFileSync('web/public/licenses/ThirdParty.txt', notices.join('\n\n'));
