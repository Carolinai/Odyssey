// 临时静态文件服务器：服务 Odyssey 的 static 目录，供 design-taste-frontend inspect.mjs 使用
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname } from 'node:path';

const ROOT = 'D:/projects/vp_controller/Odyssey/src/main/resources/static';
const PORT = 8765;
const MIME = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
};

createServer(async (req, res) => {
  try {
    const path = req.url.split('?')[0].replace(/^\//, '') || 'index.html';
    const data = await readFile(join(ROOT, path));
    res.writeHead(200, { 'Content-Type': MIME[extname(path)] || 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404); res.end('not found');
  }
}).listen(PORT, () => console.log('serving on http://localhost:' + PORT));
