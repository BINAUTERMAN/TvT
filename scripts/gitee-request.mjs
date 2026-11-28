const redirectStatuses = new Set([301, 302, 303, 307, 308]);

function trustedTarget(value) {
  const url = new URL(value);
  const official =
    url.hostname === 'gitee.com' || url.hostname.endsWith('.gitee.com');
  if (
    url.protocol !== 'https:' ||
    !official ||
    url.username ||
    url.password ||
    url.port ||
    url.hash
  )
    throw new Error('Gitee 请求地址不受信任');
  return url;
}

export function releaseId(value) {
  if (!Number.isSafeInteger(value) || value <= 0)
    throw new Error('Gitee 发行编号无效');
  return value;
}

export async function giteeRequest(value, options = {}, fetcher = fetch) {
  let url = trustedTarget(value);
  const signal = AbortSignal.timeout(180000);
  const method = (options.method || 'GET').toUpperCase();
  for (let redirects = 0; redirects <= 4; redirects++) {
    const response = await fetcher(url, {
      ...options,
      redirect: 'manual',
      signal,
    });
    if (redirectStatuses.has(response.status)) {
      await response.body?.cancel();
      // 含令牌的写入不跟随跳转；匿名附件下载只允许官方 HTTPS 域名。
      const headers = new Headers(options.headers);
      if (
        !['GET', 'HEAD'].includes(method) ||
        options.body ||
        headers.has('authorization') ||
        headers.has('cookie')
      )
        throw new Error('Gitee 写入请求发生重定向，已停止');
      const location = response.headers.get('location');
      if (!location) throw new Error('Gitee 返回了无效重定向');
      url = trustedTarget(new URL(location, url));
      continue;
    }
    if (!response.ok)
      throw new Error(`Gitee 同步失败：HTTP ${response.status}`);
    return response;
  }
  throw new Error('Gitee 重定向次数过多');
}
