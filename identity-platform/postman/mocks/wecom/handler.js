// Local contract simulation, never a production authentication provider.
const http = require('http');
const crypto = require('crypto');
const base = 'http://127.0.0.1:9099/wecom';
const callbacks = new Set([8180,8280].map(port=>`http://127.0.0.1:${port}/realms/workforce/broker/wecom-corp/endpoint`));
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
const send = (res, status, body) => { res.writeHead(status, {'Content-Type':'application/json', 'Cache-Control':'no-store'}); res.end(JSON.stringify(body)); };
http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    const q = url.searchParams;
    if (url.pathname === '/__control' && req.method === 'POST') {
      const body = req.body || {};
      await pm.state.set('wecom.mode', body.mode || 'normal');
      return send(res, 200, {mode: await pm.state.get('wecom.mode')});
    }
    if (url.pathname === '/__health') return send(res, 200, {ready:true});
    if (['/wwlogin/sso/login','/connect/oauth2/authorize'].includes(url.pathname)) {
      if (q.get('appid') !== 'mock-corp' || q.get('agentid') !== '1000001' || !callbacks.has(q.get('redirect_uri')) || !/^[a-f0-9]{64}$/.test(q.get('state') || '')) return send(res, 400, {error:'invalid authorization request'});
      await pm.state.set('wecom.flow.'+q.get('state'),{callback:q.get('redirect_uri'),expires:Date.now()+300000});
      if (url.pathname.includes('/oauth2/') && (q.get('scope') !== 'snsapi_base' || q.get('response_type') !== 'code')) return send(res, 400, {error:'invalid scope'});
      if (url.pathname.includes('/sso/') && q.get('login_type') !== 'CorpApp') return send(res, 400, {error:'invalid login_type'});
      const buttons = [['alice','已授权员工 Alice'],['outsider','未授权公司成员'],['external','非本公司成员'],['suspended','已停用员工']].map(([id, label]) =>
        `<li><a data-member="${id}" href="${escape(base + '/approve?state=' + q.get('state') + '&member=' + id)}">${label}</a></li>`).join('');
      res.writeHead(200, {'Content-Type':'text/html;charset=UTF-8','Cache-Control':'no-store'});
      return res.end(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>企业微信 · 本地模拟</title></head><body style="font:18px/2 system-ui;max-width:700px;margin:60px auto"><h1>企业微信 · 本地模拟</h1><p>此页面用于验证接入流程。真实企微上线后替换为官方授权页面。</p><ul>${buttons}</ul></body></html>`);
    }
    if (url.pathname === '/approve') {
      if (!/^[a-f0-9]{64}$/.test(q.get('state') || '') || !['alice','outsider','external','suspended'].includes(q.get('member'))) return send(res, 400, {error:'invalid test selection'});
      const flow=await pm.state.get('wecom.flow.'+q.get('state'));
      if(!flow||flow.expires<Date.now()||!callbacks.has(flow.callback))return send(res,400,{error:'test flow expired'});
      await pm.state.set('wecom.flow.'+q.get('state'),null);
      const code = crypto.randomBytes(24).toString('hex');
      await pm.state.set('wecom.code.' + code, {member:q.get('member'), expires:Date.now()+300000});
      res.writeHead(302, {Location: flow.callback + '?code=' + code + '&state=' + q.get('state'), 'Cache-Control':'no-store'});
      return res.end();
    }
    const mode = await pm.state.get('wecom.mode') || 'normal';
    if (url.pathname.startsWith('/cgi-bin/')) {
      if (mode === 'http-error') return send(res, 503, {errcode: -1, errmsg:'upstream unavailable'});
      if (mode === 'invalid-json') {res.writeHead(200, {'Content-Type':'text/html'}); return res.end('<html>upstream proxy error</html>');}
      if (mode === 'timeout') await new Promise(resolve => setTimeout(resolve, 6500));
    }
    if (url.pathname === '/cgi-bin/gettoken') {
      if (q.get('corpid') !== 'mock-corp' || q.get('corpsecret') !== 'local-mock-secret') return send(res, 200, {errcode:40001, errmsg:'invalid credential'});
      return send(res, 200, {errcode:0, errmsg:'ok', access_token:'mock-application-token', expires_in:7200});
    }
    if (url.pathname === '/cgi-bin/auth/getuserinfo') {
      if (q.get('access_token') !== 'mock-application-token') return send(res, 200, {errcode:40014, errmsg:'invalid access_token'});
      const key = 'wecom.code.' + q.get('code');
      const entry = await pm.state.get(key);
      await pm.state.set(key, null);
      if (!entry || entry.expires < Date.now()) return send(res, 200, {errcode:40029, errmsg:'invalid code'});
      return send(res, 200, entry.member === 'external' ? {errcode:0, errmsg:'ok', openid:'external-person'} : {errcode:0, errmsg:'ok', userid:entry.member});
    }
    return send(res, 501, {error:'Endpoint outside the modeled authentication contract'});
  } catch { return send(res, 500, {error:'Simulation handler failure'}); }
}).listen(process.env.PORT || 9099);
