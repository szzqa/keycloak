const {test,expect}=require('@playwright/test');
const fs=require('node:fs');
const crypto=require('node:crypto');
const kc='http://127.0.0.1:8280',portal='http://127.0.0.1:8281',api='http://127.0.0.1:8877',web='http://127.0.0.1:5178';
const settings=JSON.parse(fs.readFileSync('.local/rehearsal/settings.json','utf8'));
const journal=JSON.parse(fs.readFileSync('.local/rehearsal/migration-rehearsal-v2.json','utf8'));
// Verify the real auth callback and API session. React UI has build/type checks only.
async function login(page){
  await page.goto(api+'/auth/oidc/login');
  await page.locator('#username').fill('legacy-email@identity.test');
  await page.locator('#password').fill('TapCanvas-test-123!');
  const completed=page.waitForResponse(response=>response.url().startsWith(api+'/auth/oidc/callback'));
  await page.locator('#kc-login').click();
  const callback=await completed;expect(callback.status()).toBe(302);expect(callback.headers().location).toBe(web+'/');
  await callback.finished();return callback.url();
}
async function service(request,name,key){
  const response=await request.post(kc+'/realms/consumer/protocol/openid-connect/token',{form:{grant_type:'client_credentials',client_id:name,client_secret:settings[key]}});
  expect(response.status()).toBe(200);return (await response.json()).access_token;
}
async function admin(request,method,path,data){
  const response=await request.post(kc+'/realms/master/protocol/openid-connect/token',{form:{client_id:'admin-cli',grant_type:'password',username:settings.admin_username,password:settings.admin_password}});
  expect(response.status()).toBe(200);const token=(await response.json()).access_token;
  const result=await request.fetch(kc+'/admin/realms'+path,{method,headers:{Authorization:'Bearer '+token},data});
  expect(result.status()).toBeLessThan(300);const body=await result.text();return body?JSON.parse(body):null;
}
function totp(secret){const step=Buffer.alloc(8);step.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));const h=crypto.createHmac('sha1',Buffer.from(secret,'utf8')).update(step).digest();return ((h.readUInt32BE(h[19]&15)&0x7fffffff)%1000000).toString().padStart(6,'0');}
async function mailLink(request,email,after){
  let link;
  await expect.poll(async()=>{
    const response=await request.get('http://127.0.0.1:58025/api/v1/messages');
    for(const message of (await response.json()).messages){
      if(!message.To.some(recipient=>recipient.Address===email)||new Date(message.Created).getTime()<after)continue;
      const detail=await (await request.get('http://127.0.0.1:58025/api/v1/message/'+message.ID)).json();
      link=(detail.Text||detail.HTML||'').match(/https?:\/\/[^\s<>"']+/g)?.find(value=>value.includes('/login-actions/action-token'))?.replaceAll('&amp;','&');
      if(link)return true;
    }return false;
  },{timeout:15000}).toBe(true);return link;
}
test('email-only public registration, verification and password recovery revoke old sessions',async({page,request,browser})=>{
  await admin(request,'PUT','/consumer',{smtpServer:{host:'127.0.0.1',port:'51025',from:'accounts@identity.test',fromDisplayName:'Identity rehearsal',ssl:'false',starttls:'false',auth:'false'}});
  const email='rehearsal-'+crypto.randomUUID()+'@identity.test',password='Fixture-'+crypto.randomBytes(12).toString('hex');
  await page.goto(portal+'/auth/login');await page.locator('#kc-registration a').click();
  await expect(page.locator('#email')).toBeVisible();expect(await page.locator('#username').count()).toBe(0);
  await page.locator('#email').fill(email);const since=Date.now()-1000;
  await page.locator('[type="submit"]').first().click();await expect(page.locator('body')).toContainText(email);
  await page.goto(await mailLink(request,email,since));
  await page.locator('#password-new').fill(password);await page.locator('#password-confirm').fill(password);await page.locator('[type="submit"]').first().click();await page.waitForURL(portal+'/');
  const identity=await page.request.get(portal+'/bff/v1/me');expect(identity.status()).toBe(200);
  const context=await browser.newContext();const reset=await context.newPage();
  await reset.goto(portal+'/auth/login');await reset.locator('a[href*="reset-credentials"]').click();await reset.locator('#username').fill(email);
  const resetSince=Date.now()-1000;await reset.locator('[type="submit"]').first().click();await reset.goto(await mailLink(request,email,resetSince));
  await reset.locator('#password-new').fill(password+'-new');await reset.locator('#password-confirm').fill(password+'-new');await reset.locator('[type="submit"]').first().click();
  await expect.poll(async()=> (await page.request.get(portal+'/bff/v1/me')).status()).toBe(401);
  await context.close();
});
test('original password, stable product ID, portal SSO and CSRF',async({page})=>{
  const callback=await login(page);
  expect((await page.request.get(callback,{maxRedirects:0})).status()).toBe(400);
  expect((await page.request.get(api+'/auth/oidc/callback?state=forged&code=forged',{maxRedirects:0})).status()).toBe(400);
  const session=await page.request.get(api+'/auth/session');expect(session.status()).toBe(200);
  const user=await session.json();expect(user.user.sub).toBe('legacy-email-user');expect(user.csrfToken).toBeTruthy();
  expect((await page.request.post(api+'/auth/logout')).status()).toBe(403);
  await page.goto(portal+'/auth/login');await page.waitForURL(portal+'/');
  const identity=await page.request.get(portal+'/bff/v1/me');expect(identity.status()).toBe(200);
  expect((await identity.json()).id).toBe(journal.records['legacy-email-user'].platformUserId);
  const wallet=await page.request.get(portal+'/bff/v1/me/wallet');expect(wallet.status()).toBe(200);
  expect((await wallet.json()).policy).toBe('CONSUMER');
  await expect(page.locator('#portal-applications')).toContainText('TapCanvas');
  await page.screenshot({path:'.local/rehearsal/portal-consumer.png',fullPage:true});
  const projects=await page.request.get(api+'/projects');expect(projects.status()).toBe(200);expect(await projects.text()).toContain('original-project-legacy-email-user');
  const csrf=await page.locator('meta[name="csrf"]').getAttribute('content'), csrfHeader=await page.locator('meta[name="csrf-header"]').getAttribute('content');
  expect((await page.request.post(portal+'/bff/v1/me/credit-requests',{headers:{[csrfHeader]:csrf},data:{requestId:crypto.randomUUID(),amount:'10',reason:'consumer must not apply'}})).status()).toBe(403);
  await page.locator('form[action="/logout"] button').click();
  await expect.poll(async()=> (await page.request.get(api+'/auth/session')).status()).toBe(401);
});
test('verified no-email WeCom employee returns to original account and daily wallet',async({page,request})=>{
  await request.post('http://127.0.0.1:9099/wecom/__control',{data:{mode:'normal'}});
  const [staff]=await admin(request,'GET','/workforce/users?username=staff-employee-001&exact=true');
  for(const item of await admin(request,'GET',`/workforce/users/${staff.id}/credentials`))if(item.type==='otp')await admin(request,'DELETE',`/workforce/users/${staff.id}/credentials/${item.id}`);
  await admin(request,'POST',`/workforce/users/${staff.id}/logout`);
  await admin(request,'PUT',`/workforce/users/${staff.id}`,{enabled:true,requiredActions:['CONFIGURE_TOTP']});
  await page.goto(api+'/auth/oidc/login?method=company');
  if(await page.locator('#kc-continue').count())await page.locator('#kc-continue').click();
  await page.locator('[data-member="alice"]').click();
  await page.locator('#totpSecret').waitFor({state:'attached'});
  const secret=await page.locator('#totpSecret').inputValue();await page.locator('#totp').fill(totp(secret));await page.locator('#userLabel').fill('Isolated rehearsal');
  const completed=page.waitForResponse(response=>response.url().startsWith(api+'/auth/oidc/callback'));
  await page.locator('#saveTOTPBtn').click();const callback=await completed;expect(callback.status()).toBe(302);await callback.finished();
  const session=await page.request.get(api+'/auth/session');expect(session.status()).toBe(200);expect((await session.json()).user.sub).toBe('legacy-employee');
  await page.goto(portal+'/auth/login');await page.waitForURL(portal+'/');
  const profile=await (await page.request.get(portal+'/bff/v1/me')).json();expect(profile.policy).toBe('EMPLOYEE');
  const wallet=await (await page.request.get(portal+'/bff/v1/me/wallet')).json();expect(wallet.canApply).toBe(true);expect(wallet.resetAt).toBeTruthy();
  await page.screenshot({path:'.local/rehearsal/portal-employee.png',fullPage:true});
  const subject=journal.records['legacy-employee'].subject;
  try {
    await admin(request,'PUT',`/consumer/users/${subject}`,{email:'staff-email@identity.test',emailVerified:true});
    await admin(request,'PUT',`/consumer/users/${subject}/reset-password`,{type:'password',value:'Staff-email-fixture-123!',temporary:false});
    await page.context().clearCookies();
    await page.goto(api+'/auth/oidc/login');await page.locator('#username').fill('staff-email@identity.test');
    await page.locator('#password').fill('Staff-email-fixture-123!');
    const emailCallback=page.waitForResponse(response=>response.url().startsWith(api+'/auth/oidc/callback'));
    await page.locator('#kc-login').click();expect((await emailCallback).status()).toBe(302);
    expect((await (await page.request.get(api+'/auth/session')).json()).user.sub).toBe('legacy-employee');
    await page.goto(portal+'/auth/login');await page.waitForURL(portal+'/');
    const emailProfile=await (await page.request.get(portal+'/bff/v1/me')).json();
    expect(profile.id).toBe(journal.records['legacy-employee'].platformUserId);
    expect(emailProfile.id).toBe(profile.id);expect(emailProfile.policy).toBe('EMPLOYEE');
    const emailWallet=await (await page.request.get(portal+'/bff/v1/me/wallet')).json();expect(emailWallet.available).toBe(wallet.available);
    await admin(request,'PUT',`/workforce/users/${staff.id}`,{enabled:false});
    expect([401,403]).toContain((await page.request.get(api+'/auth/session')).status());
  } finally {
    await admin(request,'PUT',`/workforce/users/${staff.id}`,{enabled:true});
    await admin(request,'PUT',`/consumer/users/${subject}`,{email:'',emailVerified:false});
    for(const item of await admin(request,'GET',`/consumer/users/${subject}/credentials`))if(item.type==='password')await admin(request,'DELETE',`/consumer/users/${subject}/credentials/${item.id}`);
  }
});
test('disabled original account cannot sign in',async({page})=>{
  await page.goto(api+'/auth/oidc/login');await page.locator('#username').fill('disabled@identity.test');
  await page.locator('#password').fill('TapCanvas-test-123!');await page.locator('#kc-login').click();
  await expect(page.locator('body')).toContainText('账户已被禁用');
  expect((await page.request.get(api+'/auth/session')).status()).toBe(401);
});
test('scoped services, immutable operations, cancellation and postpaid gap',async({request})=>{
  const ops=await service(request,'identity-platform-operations','platform_operations');
  const serviceToken=await service(request,'tapcanvas-service','tapcanvas_service');
  const headers={Authorization:'Bearer '+serviceToken};const admin={Authorization:'Bearer '+ops};
  expect((await request.post(portal+'/internal/v1/admin/applications',{headers,data:{}})).status()).toBe(403);
  expect((await request.get(portal+'/v1/me',{headers})).status()).toBe(401);
  const user=journal.records['legacy-email-user'].platformUserId;
  const adjustment={requestId:crypto.randomUUID(),amount:'100',reason:'Isolated integration grant'};
  expect((await request.post(portal+`/internal/v1/admin/wallets/${user}/adjust`,{headers:admin,data:adjustment})).status()).toBe(200);
  const facts={operationId:'rehearsal-'+crypto.randomUUID(),localUserId:'legacy-email-user',mode:'FIXED',amount:'10',context:'fixture',requestDigest:'a'.repeat(64)};
  const reserve=()=>request.post(portal+'/internal/v1/wallet/delegated-reservations',{headers,data:facts});
  expect((await reserve()).status()).toBe(200);expect((await reserve()).status()).toBe(200);
  expect((await request.post(portal+'/internal/v1/wallet/delegated-reservations',{headers,data:{...facts,amount:'11'}})).status()).toBe(409);
  const terminal={amount:'10',evidence:'authoritative-success'};
  const settle=()=>request.post(portal+`/internal/v1/wallet/operations/${facts.operationId}/settle`,{headers,data:terminal});
  const first=await settle();expect(first.status()).toBe(200);const second=await settle();expect(await second.json()).toEqual(await first.json());
  const canceled={...facts,operationId:'rehearsal-'+crypto.randomUUID()};
  expect((await request.post(portal+'/internal/v1/wallet/reservations/cancel',{headers,data:{reservation:canceled,evidence:'never-dispatched'}})).status()).toBe(200);
  expect((await request.post(portal+'/internal/v1/wallet/delegated-reservations',{headers,data:canceled})).status()).toBe(409);
  const token={...facts,operationId:'rehearsal-'+crypto.randomUUID(),mode:'TOKEN',amount:'0'};
  expect((await request.post(portal+'/internal/v1/wallet/delegated-reservations',{headers,data:token})).status()).toBe(200);
  const billed=await request.post(portal+`/internal/v1/wallet/operations/${token.operationId}/settle`,{headers,data:{amount:'999999',evidence:'token-usage'}});
  expect(billed.status()).toBe(200);const result=await billed.json();expect(BigInt(result.charged)).toBeLessThan(BigInt(result.due));
});

test('account import preserves mapping but cannot spend until an idempotent reviewed opening',async({request})=>{
  const suffix=crypto.randomUUID(),username='opening-'+suffix;
  await admin(request,'POST','/consumer/users',{username,email:username+'@identity.test',emailVerified:true,enabled:true});
  const [identity]=await admin(request,'GET','/consumer/users?username='+username+'&exact=true');
  const headers={Authorization:'Bearer '+await service(request,'identity-platform-operations','platform_operations')};
  const product={Authorization:'Bearer '+await service(request,'tapcanvas-service','tapcanvas_service')};
  const imported={batchId:'opening-test-'+suffix,appId:'tapcanvas',localUserId:username,subject:identity.id,displayName:'Opening fixture',enabled:true,
    wallet:{policy:'CONSUMER',dailyCredits:'0',initialCredits:'20',resetTime:'00:00',requestEnabled:false,maxRequest:'0',maxDailyRequest:'0',immediateCount:0,delayedCount:0,delayMinutes:0},membership:null};
  const first=await request.post(portal+'/internal/v1/admin/imports/accounts',{headers,data:imported});
  expect(first.status()).toBe(200);const mapped=await first.json();
  expect(await (await request.post(portal+'/internal/v1/admin/imports/accounts',{headers,data:imported})).json()).toEqual(mapped);
  const facts={operationId:'opening-'+suffix,localUserId:username,mode:'FIXED',amount:'1',context:'synthetic opening',requestDigest:'b'.repeat(64)};
  const reserve=()=>request.post(portal+'/internal/v1/wallet/delegated-reservations',{headers:product,data:facts});
  expect((await reserve()).status()).toBe(409);
  const opening={requestId:crypto.randomUUID(),batchId:'opening-test-'+suffix,sourceDigest:'c'.repeat(64),reason:'Isolated approved opening fixture'};
  const url=portal+`/internal/v1/admin/wallets/${mapped.userId}/open`;
  const opened=await request.post(url,{headers,data:opening});expect(opened.status()).toBe(200);
  expect((await reserve()).status()).toBe(200);
  expect((await request.post(url,{headers,data:opening})).status()).toBe(200);
  expect((await request.post(url,{headers,data:{...opening,reason:'changed facts'}})).status()).toBe(409);
  expect((await request.post(portal+`/internal/v1/wallet/operations/${facts.operationId}/settle`,{headers:product,data:{amount:'1',evidence:'opening-test-success'}})).status()).toBe(200);
  expect((await request.get(portal+'/internal/v1/admin/applications/tapcanvas',{headers})).status()).toBe(200);
});
