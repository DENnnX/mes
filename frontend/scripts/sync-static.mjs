import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.resolve(__dirname, '../dist');
const targetStaticDir = path.resolve(__dirname, '../../lowcode-platform/src/main/resources/static');

if (!fs.existsSync(distDir)) {
  console.error('dist directory not found. Run vite build first.');
  process.exit(1);
}

fs.mkdirSync(targetStaticDir, { recursive: true });
for (const entry of fs.readdirSync(targetStaticDir)) {
  fs.rmSync(path.join(targetStaticDir, entry), { recursive: true, force: true });
}
fs.cpSync(distDir, targetStaticDir, { recursive: true });
console.log(`Synced Vite build output from ${distDir} -> ${targetStaticDir}`);
