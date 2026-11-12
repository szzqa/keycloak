const dialog = document.querySelector('#login-dialog');
for (const button of document.querySelectorAll('#open-login, [data-login]')) button.addEventListener('click', () => dialog.showModal());
document.querySelector('#close-login').addEventListener('click', () => dialog.close());
dialog.addEventListener('click', event => { if (event.target === dialog) { const r=dialog.getBoundingClientRect(); if(event.clientX<r.left || event.clientX>r.right || event.clientY<r.top || event.clientY>r.bottom) dialog.close(); } });
const status = document.querySelector('#account-status');
const csrf = () => ({[document.querySelector('meta[name="csrf-header"]').content]:document.querySelector('meta[name="csrf"]').content});
async function load() {
  if (!status) return;
  try {
    const response = await fetch('/bff/me', {cache:'no-store'});
    if (!response.ok) throw new Error(response.status === 401 ? '登录已过期，请重新登录。' : '账号服务暂不可用，请稍后重试。');
    const user = await response.json();
    document.querySelector('#user-id').textContent=user.userId;
    document.querySelector('#email').textContent=user.email;
    document.querySelector('#display-name').value=user.displayName;
    document.querySelector('#company-status').textContent=user.companyLinked ? (user.employee ? '已绑定 · 当前公司身份验证有效' : '已绑定 · 使用员工功能前请验证公司身份') : '尚未绑定';
    document.querySelector('#link-form').hidden=user.companyLinked;
    document.querySelector('#profile-data').hidden=false;
    status.textContent='欢迎回来，'+user.displayName;
  } catch(error) { status.textContent=error.message; }
}
document.querySelector('#check')?.addEventListener('click',load);
document.querySelector('#profile-form')?.addEventListener('submit',async event=>{
  event.preventDefault();
  try {
    const response=await fetch('/bff/profile',{method:'POST',headers:{...csrf(),'Content-Type':'application/json'},body:JSON.stringify({displayName:document.querySelector('#display-name').value})});
    if(!response.ok) throw new Error(response.status===400 ? '昵称须为 1–80 个有效字符。' : '保存失败，请刷新登录状态后重试。');
    await load(); status.textContent='昵称已保存，在其他产品中也会同步显示。';
  } catch(error) { status.textContent=error.message; }
});
document.querySelector('#employee-check')?.addEventListener('click',async()=>{
  const result=document.querySelector('#employee-result');
  try { const r=await fetch('/bff/employee/me',{cache:'no-store'}); result.textContent=r.ok?'公司身份已确认，可以访问员工功能。':r.status===403?'需要有效的公司身份与在职权限，请先验证公司身份。':'暂时无法验证公司身份，请稍后重试。'; }
  catch { result.textContent='网络连接失败，请稍后重试。'; }
});
load();
