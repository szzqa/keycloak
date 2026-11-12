(() => {
  const status=document.getElementById('portal-status');if(!status)return;
  const get=id=>document.getElementById(id);
  const csrf=document.querySelector('meta[name="csrf"]').content,csrfHeader=document.querySelector('meta[name="csrf-header"]').content;
  let cursor=null,requestId=crypto.randomUUID(),submittedFacts=null;
  async function call(path,body) {
    const response=await fetch('/bff/v1/me'+path,{credentials:'same-origin',cache:'no-store',method:body?'POST':'GET',
      headers:body?{'Content-Type':'application/json',[csrfHeader]:csrf}:{},...(body?{body:JSON.stringify(body)}:{})});
    if(!response.ok)throw new Error(response.status===401?'登录已过期，请重新登录。':response.status===409?'余额或申请状态发生冲突，请刷新后核对。':'账号服务暂时不可用（'+response.status+'）');
    return response.json();
  }
  const labels={INITIAL_GRANT:'首次发放',DAILY_GRANT:'周期发放',EXPIRE:'周期到期',RESERVE:'任务预占',SETTLE:'任务消费',RELEASE:'解除冻结',REQUEST_GRANT:'申请到账',ADJUST:'管理员调整',REFUND:'退款'};
  async function ledger(append=false) {
    const data=await call('/wallet/ledger'+(append&&cursor?'?before='+encodeURIComponent(cursor):''));
    if(!append)get('portal-ledger').replaceChildren();
    for(const item of data.items){const tr=document.createElement('tr');for(const text of [new Date(item.createdAt).toLocaleString(),labels[item.kind]||item.kind,item.appId||'平台',item.amount,item.reservedDelta]){const td=document.createElement('td');td.textContent=text;tr.append(td);}get('portal-ledger').append(tr);}
    cursor=data.nextCursor;get('portal-ledger-more').hidden=!cursor;
  }
  async function refresh() {
    const [user,applications,wallet,requests]=await Promise.all([call(''),call('/applications'),call('/wallet'),call('/credit-requests')]);
    document.body.dataset.accountPolicy=user.policy;
    get('portal-policy').textContent=user.policy==='EMPLOYEE'?'员工工作台':'创作空间';
    get('wallet-title').textContent=user.policy==='EMPLOYEE'?'员工积分':'个人积分';
    get('wallet-available').textContent=wallet.available+' 可用';
    get('wallet-detail').textContent=wallet.active?('冻结 '+wallet.reserved+' 积分'+(wallet.resetAt?' · 下次刷新 '+new Date(wallet.resetAt).toLocaleString():' · 不按日重置')):'统一积分尚未开账；账号与原产品数据不受影响。';
    get('credit-request-form').hidden=!wallet.canApply;get('portal-requests-title').hidden=user.policy!=='EMPLOYEE';
    get('portal-applications').replaceChildren();
    for(const app of applications.items){const a=document.createElement('a');a.className='portal-app';a.href=app.url;a.textContent=app.name+' ↗';get('portal-applications').append(a);}
    get('portal-requests').replaceChildren();for(const r of requests.items){const li=document.createElement('li');li.textContent=new Date(r.createdAt).toLocaleString()+' · '+r.amount+' 积分 · '+({approved:'已到账',processing:'处理中',rejected:'已拒绝'}[r.status]||r.status);get('portal-requests').append(li);}
    status.textContent=applications.items.length?'选择一个应用继续使用。':'当前没有可访问的应用。';await ledger();
  }
  get('credit-request-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.currentTarget.querySelector('button');button.disabled=true;
    const facts=JSON.stringify([get('credit-amount').value,get('credit-reason').value]);if(submittedFacts!==null&&facts!==submittedFacts)requestId=crypto.randomUUID();submittedFacts=facts;
    try{const result=await call('/credit-requests',{requestId,amount:get('credit-amount').value,reason:get('credit-reason').value});get('credit-request-status').textContent=result.status==='approved'?'积分已到账。':'申请已提交，正在处理。';requestId=crypto.randomUUID();submittedFacts=null;await refresh();}
    catch(error){get('credit-request-status').textContent=error.message;}finally{button.disabled=false;}});
  get('portal-ledger-more').addEventListener('click',()=>ledger(true).catch(error=>{status.textContent=error.message;}));
  refresh().catch(error=>{status.textContent=error.message;});
})();
