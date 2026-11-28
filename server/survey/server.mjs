import http from 'node:http';
import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
} from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import {
  mkdirSync,
  readFileSync,
  statSync,
  chmodSync,
  realpathSync,
} from 'node:fs';
import {
  resolve,
  dirname,
  extname,
  relative,
  isAbsolute,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';

const here = dirname(fileURLToPath(import.meta.url));
const retention = 180 * 86400000;
const maxBody = 8192;
const answerKeys = [
  'submissionId',
  'rating',
  'optimization',
  'favorites',
  'favoriteOther',
  'improvements',
  'email',
  'displayId',
  'consent',
];
const choices = new Set(['lightweight', 'animation', 'layout', 'other']);

function fail(status, message) {
  return Object.assign(new Error(message), { status });
}
export function validate(value) {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !answerKeys.includes(key))
  )
    throw fail(400, '问卷格式无效');
  if (
    typeof value.submissionId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value.submissionId,
    )
  )
    throw fail(400, '提交编号无效');
  if (
    !Number.isInteger(value.rating) ||
    value.rating < 1 ||
    value.rating > 5 ||
    value.consent !== true
  )
    throw fail(400, '请选择星级并同意提交说明');
  const cleaned = {
    submissionId: value.submissionId.toLowerCase(),
    rating: value.rating,
  };
  for (const [key, limit] of [
    ['optimization', 100],
    ['favoriteOther', 100],
    ['improvements', 100],
    ['email', 254],
    ['displayId', 40],
  ]) {
    if (
      typeof value[key] !== 'string' ||
      Array.from(value[key]).length > limit ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value[key])
    )
      throw fail(400, '填写内容无效或超过字数限制');
    cleaned[key] = value[key].trim();
  }
  if (cleaned.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleaned.email))
    throw fail(400, '邮箱格式无效');
  if (
    !Array.isArray(value.favorites) ||
    value.favorites.length > 4 ||
    value.favorites.some((item) => !choices.has(item)) ||
    new Set(value.favorites).size !== value.favorites.length
  )
    throw fail(400, '多选项无效');
  cleaned.favorites = [...value.favorites].sort();
  cleaned.consent = true;
  if (value.rating === 5) cleaned.optimization = '';
  if (!value.favorites.includes('other')) cleaned.favoriteOther = '';
  return cleaned;
}

export function openStore(path, key) {
  if (!Buffer.isBuffer(key) || key.length !== 32)
    throw new Error('必须配置 32 字节加密密钥');
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(path);
  chmodSync(path, 0o600);
  db.exec(`PRAGMA journal_mode=DELETE; PRAGMA secure_delete=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS feedback (id TEXT PRIMARY KEY, created INTEGER NOT NULL, fingerprint TEXT NOT NULL, encrypted TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS feedback_created ON feedback(created);`);
  const keyId = createHmac('sha256', key)
    .update('tvt-survey-storage-key-v1')
    .digest('hex');
  const recordedKey = db
    .prepare("SELECT value FROM metadata WHERE name = 'keyId'")
    .get();
  if (recordedKey && recordedKey.value !== keyId) {
    db.close();
    throw new Error('数据库加密密钥不匹配，拒绝启动');
  }
  db.prepare(
    "INSERT OR IGNORE INTO metadata (name, value) VALUES ('keyId', ?)",
  ).run(keyId);
  const purge = () =>
    db
      .prepare('DELETE FROM feedback WHERE created < ?')
      .run(Date.now() - retention);
  purge();
  return {
    db,
    purge,
    save(answers) {
      purge();
      const plaintext = JSON.stringify(answers);
      const fingerprint = createHmac('sha256', key)
        .update(plaintext)
        .digest('hex');
      const previous = db
        .prepare('SELECT fingerprint FROM feedback WHERE id = ?')
        .get(answers.submissionId);
      if (previous) {
        if (previous.fingerprint !== fingerprint)
          throw fail(409, '该提交编号已保存其他答案，请重新打开问卷');
        return false;
      }
      if (
        db.prepare('SELECT COUNT(*) AS count FROM feedback').get().count >=
        100000
      )
        throw fail(503, '服务暂时繁忙');
      const iv = randomBytes(12);
      const cipher = createCipheriv('aes-256-gcm', key, iv);
      cipher.setAAD(Buffer.from(answers.submissionId));
      const ciphertext = Buffer.concat([
        cipher.update(plaintext, 'utf8'),
        cipher.final(),
      ]);
      const encrypted = Buffer.concat([
        iv,
        cipher.getAuthTag(),
        ciphertext,
      ]).toString('base64');
      db.prepare(
        'INSERT INTO feedback (id, created, fingerprint, encrypted) VALUES (?, ?, ?, ?)',
      ).run(answers.submissionId, Date.now(), fingerprint, encrypted);
      return true;
    },
    *rows() {
      purge();
      for (const row of db
        .prepare('SELECT id, created, encrypted FROM feedback ORDER BY created')
        .iterate()) {
        const bytes = Buffer.from(row.encrypted, 'base64');
        const decipher = createDecipheriv(
          'aes-256-gcm',
          key,
          bytes.subarray(0, 12),
        );
        decipher.setAAD(Buffer.from(row.id));
        decipher.setAuthTag(bytes.subarray(12, 28));
        const data = Buffer.concat([
          decipher.update(bytes.subarray(28)),
          decipher.final(),
        ]);
        yield {
          receivedAt: new Date(row.created).toISOString(),
          ...JSON.parse(data.toString('utf8')),
        };
      }
    },
    close() {
      db.close();
    },
  };
}

function readBody(request) {
  return new Promise((accept, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > maxBody) {
        reject(fail(413, '请求内容过大'));
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      try {
        accept(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(fail(400, 'JSON 格式无效'));
      }
    });
    request.on('error', reject);
    request.on('aborted', () => reject(fail(400, '请求中断')));
  });
}

export function createSurveyServer({
  store,
  publicOrigin,
  staticDir,
  proxyMode = 'cloudflare',
  serveForm = true,
}) {
  if (!['direct', 'cloudflare'].includes(proxyMode))
    throw new Error('问卷代理模式无效');
  const origin = new URL(publicOrigin);
  const salt = randomBytes(32);
  const limits = new Map();
  let globalWindow = { start: Date.now(), count: 0 };
  function limited(request) {
    const now = Date.now();
    if (now - globalWindow.start >= 60000)
      globalWindow = { start: now, count: 0 };
    if (++globalWindow.count > 300) return true;
    const peer = request.socket.remoteAddress;
    if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(peer))
      throw fail(403, '仅接受本机可信代理');
    const ip =
      proxyMode === 'cloudflare'
        ? request.headers['cf-connecting-ip']
        : request.headers['x-real-ip'];
    if (typeof ip !== 'string' || !isIP(ip))
      throw fail(403, '可信代理未提供有效的客户端地址');
    const id = createHmac('sha256', salt).update(ip).digest('hex');
    for (const [entry, value] of limits)
      if (now - value.start >= 3600000) limits.delete(entry);
    if (!limits.has(id)) {
      if (limits.size >= 10000) return true;
      limits.set(id, { start: now, count: 0 });
    }
    return ++limits.get(id).count > 10;
  }
  const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('X-Frame-Options', 'DENY');
    response.setHeader(
      'Permissions-Policy',
      'camera=(), microphone=(), geolocation=()',
    );
    response.setHeader(
      'Content-Security-Policy',
      "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    );
    if (origin.protocol === 'https:')
      response.setHeader('Strict-Transport-Security', 'max-age=31536000');
    const json = (code, body) => {
      response.writeHead(code, {
        'Content-Type': 'application/json; charset=utf-8',
      });
      response.end(JSON.stringify(body));
    };
    try {
      if (request.headers.host !== origin.host)
        throw fail(403, '服务地址不匹配');
      const path = request.url;
      if (path === '/api/feedback') {
        if (request.method !== 'POST') {
          response.setHeader('Allow', 'POST');
          throw fail(405, '仅允许 POST');
        }
        if (request.headers.origin && request.headers.origin !== origin.origin)
          throw fail(403, '不允许跨站提交');
        if (limited(request)) {
          response.setHeader('Retry-After', '3600');
          throw fail(429, '提交太频繁，请稍后再试');
        }
        if (
          !/^application\/json(?:\s*;|$)/i.test(
            request.headers['content-type'] || '',
          )
        )
          throw fail(415, '仅接受 JSON');
        if (Number(request.headers['content-length'] || 0) > maxBody)
          throw fail(413, '请求内容过大');
        const value = validate(await readBody(request));
        const created = store.save(value);
        json(created ? 201 : 200, {
          accepted: true,
          receipt: value.submissionId,
        });
        return;
      }
      if (!serveForm) throw fail(404, '页面不存在');
      if (request.method !== 'GET' && request.method !== 'HEAD')
        throw fail(405, '方法不支持');
      const asset =
        path === '/' || path === '/survey.html'
          ? 'survey.html'
          : /^\/assets\/[A-Za-z0-9_.-]+\.(js|css)$/.test(path || '')
            ? path.slice(1)
            : '';
      if (!asset) throw fail(404, '页面不存在');
      const candidate = resolve(staticDir, asset);
      if (!statSync(candidate, { throwIfNoEntry: false })?.isFile())
        throw fail(404, '页面不存在');
      const target = realpathSync(candidate);
      const inside = relative(realpathSync(staticDir), target);
      if (
        inside === '..' ||
        inside.startsWith('..' + sep) ||
        isAbsolute(inside)
      )
        throw fail(404, '页面不存在');
      const type = {
        '.html': 'text/html',
        '.js': 'text/javascript',
        '.css': 'text/css',
      }[extname(target)];
      response.writeHead(200, { 'Content-Type': type + '; charset=utf-8' });
      response.end(
        request.method === 'HEAD' ? undefined : readFileSync(target),
      );
    } catch (cause) {
      if (!request.complete) response.setHeader('Connection', 'close');
      json(cause.status || 500, {
        error: cause.status ? cause.message : '服务暂时不可用，请稍后重试',
      });
    }
  });
  server.headersTimeout = 10000;
  server.requestTimeout = 15000;
  server.timeout = 15000;
  server.keepAliveTimeout = 5000;
  server.maxHeadersCount = 40;
  server.maxConnections = 128;
  server.on('close', () => store.close());
  return server;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  process.umask(0o077);
  const hex = process.env.SURVEY_ENCRYPTION_KEY;
  if (!/^[0-9a-f]{64}$/i.test(hex || ''))
    throw new Error('SURVEY_ENCRYPTION_KEY 必须为 64 位十六进制随机密钥');
  const store = openStore(
    process.env.SURVEY_DB || '/var/lib/tvt-survey/feedback.sqlite',
    Buffer.from(hex, 'hex'),
  );
  if (process.argv.includes('--purge')) {
    store.purge();
    store.close();
  } else if (process.argv.includes('--export')) {
    for (const row of store.rows())
      process.stdout.write(JSON.stringify(row) + '\n');
    store.close();
  } else {
    const publicOrigin = process.env.SURVEY_PUBLIC_ORIGIN;
    const url = new URL(publicOrigin);
    if (url.protocol !== 'https:' || url.origin !== publicOrigin)
      throw new Error(
        'SURVEY_PUBLIC_ORIGIN 必须为无路径的 HTTPS 域名或公网 IP',
      );
    const port = Number(process.env.PORT || 8787);
    if (!Number.isInteger(port) || port < 1024 || port > 65535)
      throw new Error('PORT 无效');
    const server = createSurveyServer({
      store,
      publicOrigin,
      staticDir: resolve(here, 'public'),
      proxyMode: process.env.SURVEY_PROXY_MODE || 'direct',
      serveForm: process.env.SURVEY_SERVE_FORM === 'true',
    });
    server.listen(port, '127.0.0.1', () =>
      process.stdout.write('TvT survey listening on loopback\n'),
    );
    for (const event of ['SIGINT', 'SIGTERM'])
      process.on(event, () => {
        server.close();
        server.closeIdleConnections();
      });
  }
}
