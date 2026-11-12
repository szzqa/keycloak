const {test, expect} = require('@playwright/test');
const fs = require('fs');
const crypto = require('crypto');
const settings = JSON.parse(fs.readFileSync('.local/settings.json', 'utf8'));
const kc = 'http://127.0.0.1:8180';
const product = 'http://127.0.0.1:8081';
// Both identity domains enter the same product origin.
const consumer = product;
const workforce = product;
const secondProduct = 'http://127.0.0.1:8082';
const linkedFixtures=[];
const mock = 'http://127.0.0.1:9099/wecom';
let adminToken, adminExpires=0;
async function admin(request, method, path, data) {
  if (!adminToken || Date.now()>adminExpires) {
    const r = await request.post(kc+'/realms/master/protocol/openid-connect/token', {form:{client_id:'admin-cli', grant_type:'password', username:settings.admin_username, password:settings.admin_password}});
    expect(r.status()).toBe(200); const tokens=await r.json(); adminToken=tokens.access_token; adminExpires=Date.now()+45000;
  }
  let r;
  try { r=await request.fetch(kc+'/admin/realms'+path,{method,data,headers:{Authorization:'Bearer '+adminToken}}); }
  catch { throw new Error('Local admin request interrupted: '+method+' '+path); }
  expect(r.status(), method+' '+path).toBeLessThan(300);
  const body=await r.text(); return body ? JSON.parse(body) : null;
}
async function user(request, realm, name) { return (await admin(request,'GET',`/${realm}/users?username=${encodeURIComponent(name)}&exact=true`))[0]; }
function totp(secret) {
  const step=Buffer.alloc(8); step.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));
  const hash=crypto.createHmac('sha1',Buffer.from(secret,'utf8')).update(step).digest(); const offset=hash[19]&15;
  return ((hash.readUInt32BE(offset)&0x7fffffff)%1000000).toString().padStart(6,'0');
}
async function mailLink(request, email, after) {
  let link;
  await expect.poll(async () => {
    const r=await request.get('http://127.0.0.1:8025/api/v1/messages');
    for(const m of (await r.json()).messages) {
      if (!m.To.some(x=>x.Address===email) || (after && new Date(m.Created).getTime()<after)) continue;
      const detail=await (await request.get('http://127.0.0.1:8025/api/v1/message/'+m.ID)).json();
      const matches=(detail.Text || detail.HTML || '').match(/http:\/\/127\.0\.0\.1:8180\/[^\s<>"']+/g);
      link=matches?.find(x=>x.includes('/login-actions/action-token'))?.replaceAll('&amp;','&');
      if(link) return true;
    }
    return false;
  },{timeout:15000}).toBe(true);
  return link;
}
async function consumerLogin(page, email, password) {
  await page.goto(consumer+'/auth/login');
  await page.locator('#username').fill(email); await page.locator('#password').fill(password);
  await page.locator('#kc-login').click(); await page.waitForURL(consumer+'/');
}
async function staffLogin(page, request) {
  const staff=await user(request,'workforce','staff-employee-001');
  await admin(request,'PUT','/workforce/users/'+staff.id,{enabled:true});
  // Reset only the dedicated local fixture's OTP, forcing real enrollment in every run.
  for(const c of await admin(request,'GET',`/workforce/users/${staff.id}/credentials`)) if(c.type==='otp') await admin(request,'DELETE',`/workforce/users/${staff.id}/credentials/${c.id}`);
  await admin(request,'POST',`/workforce/users/${staff.id}/logout`);
  const fixture=await consumerFixture(request); linkedFixtures.push(fixture.id);
  await consumerLogin(page,fixture.email,fixture.password);
  const before=await (await page.request.get(product+'/bff/me')).json();
  await page.locator('#link-form button').click();
  await page.locator('#kc-continue').click();
  await page.locator('[data-member="alice"]').click();
  await page.locator('#totpSecret').waitFor({state:'attached'});
  const secret=await page.locator('#totpSecret').inputValue();
  await page.locator('#totp').fill(totp(secret)); await page.locator('#userLabel').fill('Local acceptance test');
  await page.locator('#saveTOTPBtn').click(); await page.waitForURL(workforce+'/');
  return {...staff, platform:fixture, userId:before.userId, otp:secret};
}

test.afterEach(async ({request})=>{ while(linkedFixtures.length) await admin(request,'DELETE','/consumer/users/'+linkedFixtures.pop()); });

test.beforeEach(async ({request}) => {
  const response=await request.post(mock+'/__control',{data:{mode:'normal'}}); expect(response.status()).toBe(200);
});

test('To C 注册、验证邮箱、登录、密码重置撤销旧会话', async ({page, browser, request}) => {
  const email=`accept-${Date.now()}@identity.test`, password='Identity-'+crypto.randomBytes(12).toString('hex');
  await page.goto(consumer+'/auth/login');
  await page.locator('#kc-registration a').click();
  await page.locator('#email').fill(email);
  await page.locator('input[type=submit],button[type=submit]').first().click();
  await expect(page.locator('body')).toContainText(email);
  expect((await page.request.get(consumer+'/bff/me')).status()).toBe(401);
  const verify=await mailLink(request,email);
  await page.goto(verify);
  await page.locator('#password-new').fill(password); await page.locator('#password-confirm').fill(password);
  await page.locator('input[type=submit],button[type=submit]').first().click(); await page.waitForURL(consumer+'/');
  const me=await page.request.get(consumer+'/bff/me'); expect(me.status()).toBe(200);
  const firstIdentity=await me.json(); expect(firstIdentity.realm).toBe('consumer');
  expect((await page.request.get(product+'/bff/employee/me')).status()).toBe(403);
  const other=await browser.newContext(); const second=await other.newPage();
  await consumerLogin(second,email,password); expect((await second.request.get(consumer+'/bff/me')).status()).toBe(200);
  const recovery=await browser.newContext(); const reset=await recovery.newPage();
  await reset.goto(consumer+'/auth/login');
  await reset.locator('a[href*="reset-credentials"]').click();
  await reset.locator('#username').fill(email); const since=Date.now()-1000;
  await reset.locator('input[type=submit],button[type=submit]').first().click();
  const resetLink=await mailLink(request,email,since); await reset.goto(resetLink);
  await reset.locator('#password-new').fill(password+'-changed'); await reset.locator('#password-confirm').fill(password+'-changed');
  // Deliberately do not select the logout-sessions checkbox.
  if(await reset.locator('#logout-sessions').count()) await reset.locator('#logout-sessions').uncheck();
  await reset.locator('input[type=submit],button[type=submit]').first().click();
  await expect.poll(async()=> (await second.request.get(consumer+'/bff/me')).status()).toBe(401);
  await expect.poll(async()=> (await page.request.get(consumer+'/bff/me')).status()).toBe(401);
  const replay=await browser.newContext(); const replayPage=await replay.newPage(); await replayPage.goto(resetLink);
  await expect(replayPage.locator('#password-new')).toHaveCount(0);
  await recovery.close(); await replay.close(); await other.close();
});

test('To C 重复邮箱、回调白名单和 CSRF 保护', async ({page, request}) => {
  const r=await request.get(kc+'/realms/consumer/protocol/openid-connect/auth', {params:{client_id:'consumer-web', redirect_uri:'https://example.invalid/callback', response_type:'code', scope:'openid'},maxRedirects:0});
  expect(r.status()).toBe(400);
  expect((await request.post(consumer+'/logout')).status()).toBe(403);
  expect((await request.get(consumer+'/api/me')).status()).toBe(401);
  expect((await request.get(consumer+'/login/oauth2/code/consumer?code=forged&state=forged',{maxRedirects:0})).status()).toBeGreaterThanOrEqual(300);
  const email=`duplicate-${Date.now()}@identity.test`;
  await admin(request,'POST','/consumer/users',{username:email,email,enabled:true,emailVerified:true});
  await page.goto(consumer+'/auth/login'); await page.locator('#kc-registration a').click();
  await page.locator('#email').fill(email);
  await page.locator('input[type=submit],button[type=submit]').first().click();
  expect((await admin(request,'GET','/consumer/users?email='+encodeURIComponent(email)+'&exact=true')).length).toBe(1);
  expect((await page.request.get(consumer+'/bff/me')).status()).toBe(401);
});

for (const member of ['outsider','external','suspended']) {
  test('员工拒绝：'+member, async ({page}) => {
    await page.goto(workforce+'/auth/login/wecom');
    const response=await Promise.all([page.waitForResponse(r=>r.url().includes('/broker/wecom-corp/endpoint')),page.locator(`[data-member="${member}"]`).click()]);
    expect(response[0].status()).toBe(403);
    expect((await page.request.get(workforce+'/bff/me')).status()).toBe(401);
  });
}

test('员工完成企微与 TOTP；SSO 复用；停用立即阻止现有会话', async ({page, request}) => {
  const staff=await staffLogin(page,request);
  const employee=await page.request.get(product+'/bff/employee/me'); expect(employee.status()).toBe(200);
  expect((await employee.json()).realm).toBe('consumer');
  expect((await page.request.get(workforce+'/bff/me')).status()).toBe(200);
  await page.goto(workforce+'/auth/login'); await page.waitForURL(workforce+'/');
  expect((await page.request.get(workforce+'/bff/me')).status()).toBe(200);
  try {
    // Do not invoke logout: introspection itself must reject a disabled user's existing token.
    await admin(request,'PUT','/workforce/users/'+staff.id,{enabled:false});
    expect((await page.request.get(workforce+'/bff/employee/me')).status()).toBe(403);
    expect((await page.request.get(workforce+'/bff/me')).status()).toBe(200);
  } finally { await admin(request,'PUT','/workforce/users/'+staff.id,{enabled:true}); await admin(request,'POST',`/workforce/users/${staff.id}/logout`); }
});

test('企微 state 重放、伪造与上游异常均拒绝', async ({page,request}) => {
  expect((await request.get(kc+'/realms/workforce/broker/wecom-corp/endpoint?state=forged&code=forged')).status()).toBe(400);
  await page.goto(workforce+'/auth/login/wecom');
  let callback;
  page.on('request',r=>{if(r.url().includes('/broker/wecom-corp/endpoint?'))callback=r.url();});
  await page.locator('[data-member="outsider"]').click();
  expect((await request.get(callback)).status()).toBe(400);
  for(const mode of ['http-error','invalid-json','timeout']) {
    await request.post(mock+'/__control',{data:{mode}});
    await page.goto(workforce+'/auth/login/wecom');
    const [response]=await Promise.all([page.waitForResponse(r=>r.url().includes('/broker/wecom-corp/endpoint')),page.locator('[data-member="alice"]').click()]);
    expect(response.status()).toBe(502);
    expect((await page.request.get(workforce+'/bff/me')).status()).toBe(401);
  }
  await request.post(mock+'/__control',{data:{mode:'normal'}});
});

test('企业微信客户端内授权入口使用同一成员键', async ({browser,request}) => {
  const context=await browser.newContext({userAgent:'Mozilla/5.0 wxwork/4.1.0'});
  const page=await context.newPage(); await page.goto(workforce+'/auth/login/wecom');
  expect(page.url()).toContain('/connect/oauth2/authorize');
  const [response]=await Promise.all([page.waitForResponse(r=>r.url().includes('/broker/wecom-corp/endpoint')),page.locator('[data-member="alice"]').click()]);
  expect(response.status()).toBeLessThan(400);
  await expect(page.locator('#otp, #totp')).toBeVisible();
  expect((await admin(request,'GET','/workforce/users?username=staff-employee-001&exact=true')).length).toBe(1);
  await context.close();
});

async function consumerFixture(request) {
  const email=`protocol-${crypto.randomBytes(6).toString('hex')}@identity.test`;
  const password='Identity-'+crypto.randomBytes(12).toString('hex');
  await admin(request,'POST','/consumer/users',{username:email,email,enabled:true,emailVerified:true,
    credentials:[{type:'password',value:password,temporary:false}]});
  return {email,password,id:(await user(request,'consumer',email)).id};
}

async function authorizationCode(page, fixture, clientId='consumer-web') {
  const verifier=crypto.randomBytes(32).toString('base64url');
  const redirect=consumer+'/login/oauth2/code/consumer';
  let captured;
  page.on('request',req=> { const url=new URL(req.url()); if(url.origin===consumer && url.pathname==='/login/oauth2/code/consumer') captured=url; });
  const params=new URLSearchParams({client_id:clientId,redirect_uri:redirect,response_type:'code',scope:'openid profile email',
    state:crypto.randomBytes(16).toString('hex'),nonce:crypto.randomBytes(16).toString('hex'),
    code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256'});
  await page.goto(kc+'/realms/consumer/protocol/openid-connect/auth?'+params);
  await page.locator('#username').fill(fixture.email); await page.locator('#password').fill(fixture.password);
  await page.locator('#kc-login').click();
  await expect.poll(()=>!!captured,{timeout:10000}).toBe(true);
  expect(captured.searchParams.get('error')).toBeNull();
  expect(captured.searchParams.has('code')).toBe(true);
  return {code:captured.searchParams.get('code'),code_verifier:verifier,redirect_uri:redirect};
}

test('真实令牌校验 issuer/audience；授权码与刷新令牌不可重放', async ({page,request,browser}) => {
  const fixture=await consumerFixture(request);
  let extraClient;
  try {
    const grant=await authorizationCode(page,fixture);
    const form={...grant,client_id:'consumer-web',client_secret:settings.consumer_web,grant_type:'authorization_code'};
    const endpoint=kc+'/realms/consumer/protocol/openid-connect/token';
    const response=await request.post(endpoint,{form}); expect(response.status()).toBe(200);
    const tokens=await response.json();
    expect((await request.get(consumer+'/api/me',{headers:{Authorization:'Bearer '+tokens.access_token}})).status()).toBe(200);
    expect((await request.get(product+'/api/employee/me',{headers:{Authorization:'Bearer '+tokens.access_token}})).status()).toBe(403);
    expect((await request.get(product+'/api/me',{headers:{Authorization:'Bearer '+adminToken}})).status()).toBe(401);
    const refresh={client_id:'consumer-web',client_secret:settings.consumer_web,grant_type:'refresh_token',refresh_token:tokens.refresh_token};
    expect((await request.post(endpoint,{form:refresh})).status()).toBe(200);
    expect((await request.post(endpoint,{form:refresh})).status()).toBe(400);
    expect((await request.post(endpoint,{form})).status()).toBe(400);

    const clientId='accept-audience-'+crypto.randomBytes(5).toString('hex'),secret=crypto.randomBytes(24).toString('hex');
    await admin(request,'POST','/consumer/clients',{clientId,secret,protocol:'openid-connect',publicClient:false,
      standardFlowEnabled:true,directAccessGrantsEnabled:false,fullScopeAllowed:false,
      defaultClientScopes:['basic','profile','email'],redirectUris:[consumer+'/login/oauth2/code/consumer'],
      attributes:{'pkce.code.challenge.method':'S256'}});
    extraClient=(await admin(request,'GET','/consumer/clients?clientId='+clientId))[0];
    const context=await browser.newContext(), wrong=await context.newPage();
    const wrongGrant=await authorizationCode(wrong,fixture,clientId);
    const wrongResponse=await request.post(endpoint,{form:{...wrongGrant,client_id:clientId,client_secret:secret,grant_type:'authorization_code'}});
    expect(wrongResponse.status()).toBe(200);
    expect((await request.get(consumer+'/api/me',{headers:{Authorization:'Bearer '+(await wrongResponse.json()).access_token}})).status()).toBe(401);
    await context.close();
  } finally {
    if(extraClient) await admin(request,'DELETE','/consumer/clients/'+extraClient.id);
    await admin(request,'DELETE','/consumer/users/'+fixture.id);
  }
});

test('PKCE 校验错误拒绝换码；业务退出清除 Cookie 与 Keycloak SSO', async ({page,request,browser}) => {
  const fixture=await consumerFixture(request);
  try {
    const grant=await authorizationCode(page,fixture);
    const response=await request.post(kc+'/realms/consumer/protocol/openid-connect/token',{form:{...grant,
      code_verifier:crypto.randomBytes(32).toString('base64url'),client_id:'consumer-web',client_secret:settings.consumer_web,grant_type:'authorization_code'}});
    expect(response.status()).toBe(400);
    const context=await browser.newContext(), app=await context.newPage();
    await consumerLogin(app,fixture.email,fixture.password);
    expect((await app.request.get(consumer+'/bff/me')).status()).toBe(200);
    const forged=await app.request.post(consumer+'/logout/connect/back-channel/consumer',{form:{logout_token:'forged'}});
    expect(forged.status()).toBeGreaterThanOrEqual(400);
    expect((await app.request.get(consumer+'/bff/me')).status()).toBe(200);
    await app.locator('form[action="/logout"] button').click(); await app.waitForURL(consumer+'/');
    expect((await app.request.get(consumer+'/bff/me')).status()).toBe(401);
    await app.goto(consumer+'/auth/login'); await expect(app.locator('#username')).toBeVisible();
    await expect(app.locator('#kc-login')).toContainText('登录');
    await context.close();
  } finally { await admin(request,'DELETE','/consumer/users/'+fixture.id); }
});

test('BFF 并发请求安全轮换刷新令牌', async ({page,request}) => {
  const fixture=await consumerFixture(request);
  const client=(await admin(request,'GET','/consumer/clients?clientId=consumer-web'))[0];
  try {
    await admin(request,'PUT','/consumer/clients/'+client.id,{attributes:{...client.attributes,'access.token.lifespan':'30'}});
    await consumerLogin(page,fixture.email,fixture.password);
    // The token is within Spring Security's 60-second refresh window from issuance.
    const responses=await Promise.all(Array.from({length:8},()=>page.request.get(consumer+'/bff/me')));
    expect(responses.map(r=>r.status())).toEqual(Array(8).fill(200));
    for(const response of responses) {
      const body=await response.json(); expect(body.subject).toBe(fixture.id);
      expect(body).not.toHaveProperty('access_token'); expect(body).not.toHaveProperty('refresh_token');
    }
  } finally {
    await admin(request,'PUT','/consumer/clients/'+client.id,{attributes:client.attributes});
    await admin(request,'DELETE','/consumer/users/'+fixture.id);
  }
});

test('修改邮箱先验证新邮箱，身份主键保持不变', async ({page,request,browser}) => {
  const fixture=await consumerFixture(request);
  const nextEmail='changed-'+crypto.randomBytes(6).toString('hex')+'@identity.test';
  try {
    await consumerLogin(page,fixture.email,fixture.password);
    const verifier=crypto.randomBytes(32).toString('base64url');
    const params=new URLSearchParams({client_id:'consumer-web',redirect_uri:consumer+'/login/oauth2/code/consumer',
      response_type:'code',scope:'openid profile email',state:crypto.randomBytes(16).toString('hex'),
      code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256',kc_action:'UPDATE_EMAIL'});
    await page.goto(kc+'/realms/consumer/protocol/openid-connect/auth?'+params);
    await page.locator('#email').fill(nextEmail); await page.locator('#kc-submit').click();
    expect((await admin(request,'GET','/consumer/users/'+fixture.id)).email).toBe(fixture.email);
    const link=await mailLink(request,nextEmail); await page.goto(link);
    const updated=await admin(request,'GET','/consumer/users/'+fixture.id);
    expect(updated.email).toBe(nextEmail); expect(updated.emailVerified).toBe(true);
    const context=await browser.newContext(), changed=await context.newPage();
    await consumerLogin(changed,nextEmail,fixture.password);
    const response=await changed.request.get(consumer+'/bff/me'); expect(response.status()).toBe(200);
    expect((await response.json()).subject).toBe(fixture.id);
    await context.close();
  } finally { await admin(request,'DELETE','/consumer/users/'+fixture.id); }
});

test('登录弹窗可用，绑定后两种登录方式使用同一平台账号', async ({page,request}) => {
  test.setTimeout(90000);
  await page.goto(product+'/');
  await expect(page.locator('header .brand')).toContainText('创作工作台 A');
  await page.locator('#open-login').click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('link',{name:'邮箱登录 / 注册'})).toHaveAttribute('href','/auth/login');
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible();
  const staff=await staffLogin(page,request);
  const linked=await (await page.request.get(product+'/bff/me')).json();
  expect(linked.userId).toBe(staff.userId); expect(linked.subject).toBe(staff.platform.id); expect(linked.companyLinked).toBe(true);
  const second=await page.context().newPage(); await second.goto(secondProduct+'/auth/login'); await second.waitForURL(secondProduct+'/');
  expect((await second.request.get(secondProduct+'/bff/employee/me')).status()).toBe(200); await second.close();
  await page.locator('form[action="/logout"] button').click(); await page.waitForURL(product+'/');
  await page.goto(product+'/auth/login/wecom'); await page.locator('[data-member="alice"]').click();
  // OTP secrets are not exported by Admin REST. Retain the enrollment secret only in this test process.
  await page.waitForTimeout(31000 - (Date.now()%30000)); // Keycloak rejects reuse of the enrollment OTP step.
  await page.locator('#otp').fill(totp(staff.otp)); await page.locator('#kc-login').click(); await page.waitForURL(product+'/');
  const again=await (await page.request.get(product+'/bff/me')).json(); expect(again.userId).toBe(linked.userId); expect(again.employee).toBe(true);
  await page.locator('form[action="/logout"] button').click(); await page.waitForURL(product+'/');
  await consumerLogin(page,staff.platform.email,staff.platform.password);
  const email=await (await page.request.get(product+'/bff/me')).json(); expect(email.userId).toBe(linked.userId); expect(email.companyLinked).toBe(true); expect(email.employee).toBe(false);
  expect((await page.request.get(product+'/bff/employee/me')).status()).toBe(403);
  await page.goto(product+'/auth/login/wecom'); await page.locator('[data-member="alice"]').click();
  await page.waitForTimeout(31000-(Date.now()%30000));
  await page.locator('#otp').fill(totp(staff.otp)); await page.locator('#kc-login').click(); await page.waitForURL(product+'/');
  expect((await page.request.get(product+'/bff/employee/me')).status()).toBe(200);
  expect((await (await page.request.get(product+'/bff/me')).json()).userId).toBe(staff.userId);
});

test('两个产品共享资料和 SSO；Cookie、API audience 独立；退出同步', async ({page,request,browser}) => {
  const fixture=await consumerFixture(request);
  try {
    await consumerLogin(page,fixture.email,fixture.password);
    const before=await (await page.request.get(product+'/bff/me')).json();
    await page.locator('#display-name').fill('跨产品的同一个我'); await page.locator('#profile-form button').click();
    await expect(page.locator('#account-status')).toContainText('昵称已保存');
    const second=await page.context().newPage(); await second.goto(secondProduct+'/auth/login'); await second.waitForURL(secondProduct+'/');
    const result=await second.request.get(secondProduct+'/bff/me'); expect(result.status()).toBe(200);
    const profile=await result.json(); expect(profile.userId).toBe(before.userId); expect(profile.subject).toBe(fixture.id); expect(profile.displayName).toBe('跨产品的同一个我'); expect(profile.product).toBe('product-b');
    const cookies=(await page.context().cookies()).map(c=>c.name); expect(cookies).toContain('PRODUCT_SESSION'); expect(cookies).toContain('PRODUCT_B_SESSION');
    const context=await browser.newContext(); const raw=await context.newPage(); const grant=await authorizationCode(raw,fixture);
    const tokens=await (await request.post(kc+'/realms/consumer/protocol/openid-connect/token',{form:{...grant,client_id:'consumer-web',client_secret:settings.consumer_web,grant_type:'authorization_code'}})).json();
    expect((await request.get(secondProduct+'/api/me',{headers:{Authorization:'Bearer '+tokens.access_token}})).status()).toBe(401);
    await context.close();
    expect((await page.request.post(product+'/bff/profile',{data:{displayName:'forged'}})).status()).toBe(403);
    expect((await page.request.post(product+'/account/link/company')).status()).toBe(403);
    const csrfToken=await page.locator('meta[name="csrf"]').getAttribute('content');
    const csrfHeader=await page.locator('meta[name="csrf-header"]').getAttribute('content');
    for (const displayName of ['', 'x'.repeat(81), 'bad\u0001name']) {
      expect((await page.request.post(product+'/bff/profile',{data:{displayName},headers:{[csrfHeader]:csrfToken}})).status()).toBe(400);
    }
    await page.locator('form[action="/logout"] button').click(); await page.waitForURL(product+'/');
    await expect.poll(async()=> (await second.request.get(secondProduct+'/bff/me')).status()).toBe(401);
  } finally { await admin(request,'DELETE','/consumer/users/'+fixture.id); }
});

test('公司会话失效立即撤销员工权限，个人账号独立会话仍可用', async ({page,request,browser}) => {
  const staff=await staffLogin(page,request);
  const independent=await browser.newContext(), personal=await independent.newPage();
  try {
    await consumerLogin(personal,staff.platform.email,staff.platform.password);
    expect((await personal.request.get(product+'/bff/employee/me')).status()).toBe(403);
    await admin(request,'POST',`/workforce/users/${staff.id}/logout`);
    const result=await page.request.get(product+'/bff/employee/me'); expect([401,403]).toContain(result.status());
    expect((await personal.request.get(product+'/bff/me')).status()).toBe(200);
  } finally { await independent.close(); }
});


test('已绑定的企业身份不能被另一个账号占用', async ({page,request,browser}) => {
  const staff=await staffLogin(page,request);
  const other=await consumerFixture(request); const context=await browser.newContext(), second=await context.newPage();
  try {
    await consumerLogin(second,other.email,other.password);
    await second.locator('#link-form button').click(); await second.locator('#kc-continue').click();
    await second.locator('[data-member="alice"]').click();
    await second.waitForTimeout(31000-(Date.now()%30000));
    await second.locator('#otp').fill(totp(staff.otp)); await second.locator('#kc-login').click();
    await expect(second.locator('#kc-error-message')).toBeVisible();
    const original=await admin(request,'GET',`/consumer/users/${staff.platform.id}/federated-identity`);
    expect(original.some(x=>x.identityProvider==='company' && x.userId===staff.id)).toBe(true);
    expect(await admin(request,'GET',`/consumer/users/${other.id}/federated-identity`)).toEqual([]);
    expect((await second.request.get(product+'/bff/employee/me')).status()).toBe(403);
  } finally { await context.close(); await admin(request,'DELETE','/consumer/users/'+other.id); }
});

test('未绑定企业身份不会自动创建重复的平台账号', async ({page,request}) => {
  const staff=await user(request,'workforce','staff-employee-001');
  await admin(request,'POST',`/workforce/users/${staff.id}/logout`);
  for(const c of await admin(request,'GET',`/workforce/users/${staff.id}/credentials`)) if(c.type==='otp') await admin(request,'DELETE',`/workforce/users/${staff.id}/credentials/${c.id}`);
  const count=await admin(request,'GET','/consumer/users/count');
  await page.goto(product+'/auth/login/wecom'); await page.locator('[data-member="alice"]').click();
  const secret=await page.locator('#totpSecret').inputValue();
  await page.locator('#totp').fill(totp(secret)); await page.locator('#userLabel').fill('Unlinked acceptance');
  await page.locator('#saveTOTPBtn').click(); await expect(page.locator('#kc-error-message')).toBeVisible();
  expect((await page.request.get(product+'/bff/me')).status()).toBe(401);
  expect(await admin(request,'GET','/consumer/users/count')).toBe(count);
});
