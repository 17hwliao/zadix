'use strict';
const byId=id=>document.getElementById(id);
let releaseHome='https://github.com/17hwliao/zadix/releases';
let releaseApi='https://api.github.com/repos/17hwliao/zadix/releases?per_page=20';
const native=location.hostname==='zadix.local';
let currentVersion='', currentBuild, port, state, noticeTimer, toggling=false;
let localRules, previewPermission=false, previewRunning=false, previewTile=false, updatePending=false;
const labels={overview:'概览',rules:'规则库',guide:'使用指南',settings:'设置'};
const categories={opening:'开屏广告',fullscreen:'全屏广告',partial:'局部广告',inline:'分段广告',mixed:'综合广告',other:'其他规则'};
const sampleApps={
  opening:[['QQ','com.tencent.mobileqq'],['百度网盘','com.baidu.netdisk']],
  fullscreen:[['淘宝','com.taobao.taobao']],
  partial:[['哔哩哔哩','tv.danmaku.bili']],
  inline:[['知乎','com.zhihu.android']],
  mixed:[],
  other:[['汽水音乐','com.luna.music']]
};
const samplePaused=new Set(), pendingOpening=new Set();
let catalog=null, catalogRequestId=0;
const pending=new Set();
const dialog=byId('guide-dialog');
let dialogAction, dialogOrigin;
let releaseTag;
function text(id,value){byId(id).textContent=value;}
function feedback(id,message,error=false){const el=byId(id);el.textContent=message;el.dataset.error=String(error);}
function notice(message){clearTimeout(noticeTimer);text('notice',message);byId('notice').hidden=false;noticeTimer=setTimeout(()=>byId('notice').hidden=true,3800);}
function version(){
  const label=currentVersion?'v'+currentVersion+' · Build '+currentBuild:'正在读取版本';
  document.querySelectorAll('.version').forEach(el=>el.textContent=label);
}
function route(){
  const requested=location.hash.slice(1);
  const category=requested.startsWith('rules/')?requested.slice(6):'';
  const inCategory=Object.hasOwn(categories,category);
  const page=inCategory?'rule-category':Object.hasOwn(labels,requested)?requested:'overview';
  const nav=inCategory?'rules':page;
  document.querySelectorAll('.page').forEach(el=>el.hidden=el.id!==page);
  document.querySelectorAll('.rail nav a').forEach(a=>a.getAttribute('href')==='#'+nav?a.setAttribute('aria-current','page'):a.removeAttribute('aria-current'));
  text('section-label',labels[nav]);
  if(inCategory) openCatalog(category);
  else catalog=null;
  window.scrollTo(0,0);
}
function zadixBack(){
  if(dialog.open){dialog.close();return true;}
  if(catalog){location.hash='rules';return true;}
  if(location.hash && location.hash!=='#overview'){location.hash='overview';return true;}
  return false;
}

function openCatalog(category){
  catalog={category,apps:new Map(),next:0,loading:false};
  text('category-title',categories[category]);
  text('category-state',(native?'':'示例应用 · ')+(category==='opening'?'开关在「实时跳过」开启时生效':'当前版本仅展示'));
  byId('catalog-apps').replaceChildren();
  byId('catalog-card').hidden=true;
  byId('catalog-more').hidden=true;
  feedback('catalog-message','');
  loadCatalog();
}

function loadCatalog(){
  if(!catalog||catalog.loading||catalog.next<0)return;
  const {category,next}=catalog;
  catalog.loading=true;
  catalog.requestId=++catalogRequestId;
  feedback('catalog-message',next===0?'正在读取应用…':'');
  byId('catalog-more').disabled=true;
  if(!native){
    const apps=sampleApps[category].map(([label,packageName])=>({
      label,packageName,available:category==='opening',enabled:!samplePaused.has(packageName)
    }));
    catalogResult({requestId:catalog.requestId,apps,next:-1});
  } else if(!send('catalog:'+category+':'+next+':'+catalog.requestId)){
    catalogResult({requestId:catalog.requestId,error:'连接尚未建立，请重试。'});
  }
}

function setOpeningButton(button,app){
  button.disabled=pendingOpening.has(app.packageName);
  button.setAttribute('aria-pressed',String(app.enabled));
  button.textContent=app.enabled?'已开启':'已关闭';
}

function addCatalogApp(app){
  if(catalog.apps.has(app.packageName))return;
  const item=document.createElement('li'), detail=document.createElement('div');
  item.className='catalog-app';
  const name=document.createElement('strong'), pkg=document.createElement('p');
  name.textContent=app.label;
  pkg.textContent=app.packageName;
  detail.append(name,pkg);
  item.append(detail);
  if(catalog.category==='opening'&&app.available){
    const button=document.createElement('button');
    button.className='secondary';
    button.type='button';
    button.setAttribute('aria-label',app.label+'开屏拦截');
    button.addEventListener('click',()=>chooseOpening(app));
    app.button=button;
    setOpeningButton(button,app);
    item.append(button);
  } else if(catalog.category==='opening'){
    const status=document.createElement('span');
    status.className='badge';
    status.textContent='无可用规则';
    item.append(status);
  }
  catalog.apps.set(app.packageName,app);
  byId('catalog-apps').append(item);
}

function catalogResult(result){
  if(!catalog||result.requestId!==catalog.requestId)return;
  catalog.loading=false;
  const more=byId('catalog-more');
  more.disabled=false;
  if(result.error){
    feedback('catalog-message',result.error,true);
    more.textContent='重试';
    more.hidden=false;
    return;
  }
  result.apps.forEach(addCatalogApp);
  catalog.next=result.next;
  byId('catalog-card').hidden=catalog.apps.size===0;
  feedback('catalog-message',catalog.apps.size?'':'没有此分类的应用。');
  more.textContent='加载更多';
  more.hidden=catalog.next<0;
}

function chooseOpening(app){
  if(pendingOpening.has(app.packageName))return;
  const enabled=!app.enabled;
  pendingOpening.add(app.packageName);
  setOpeningButton(app.button,app);
  if(native){
    if(!send('opening:'+app.packageName+':'+(enabled?'1':'0'))){
      openingResult({packageName:app.packageName,error:'连接尚未建立，请重试。'});
    }
  } else {
    if(enabled)samplePaused.delete(app.packageName);
    else samplePaused.add(app.packageName);
    openingResult({packageName:app.packageName,enabled});
  }
}

function openingResult(result){
  pendingOpening.delete(result.packageName);
  const app=catalog?.apps.get(result.packageName);
  if(!app?.button)return;
  if(result.error){
    feedback('catalog-message',result.error,true);
    if(result.refresh){
      openCatalog('opening');
      return;
    }
  } else {
    app.enabled=result.enabled;
    feedback('catalog-message','');
  }
  setOpeningButton(app.button,app);
}
byId('catalog-more').addEventListener('click',loadCatalog);
function showDialog(title,copy,label,action){
  dialogOrigin=document.activeElement;
  text('dialog-title',title);text('dialog-copy',copy);text('dialog-confirm',label);
  dialogAction=action;dialog.showModal();byId('dialog-confirm').focus();
}
dialog.addEventListener('close',()=>dialogOrigin?.focus());
byId('dialog-confirm').addEventListener('click',()=>{const action=dialogAction;dialog.close();action?.();});
function render(s){
  state=s;
  byId('hero').dataset.phase=s.phase;
  text('live-chip-text',s.label);
  text('live-title',({RUNNING:'防护已开启',PAUSED:'防护已暂停',PERMISSION:'开启实时跳过',STARTING:'正在启动',PAUSING:'正在暂停',CLEANING:'清理进行中',RECOVERY:'实时跳过需检查',UNAVAILABLE:'服务暂不可用'})[s.phase]||'实时跳过');
  const defaultDetail=({RUNNING:'符合规则的开屏广告会自动跳过。',PAUSED:'自动跳过已暂停，随时可以恢复。',PERMISSION:'授权后，Zadix 可识别广告的跳过控件。',UNAVAILABLE:'无法读取服务状态，请重新连接。'})[s.phase];
  text('live-detail',s.detail||defaultDetail||'正在等待系统确认。');
  const button=byId('live-action');
  button.textContent=s.phase==='UNAVAILABLE'?'重新连接':!s.permission?'前往授权并开启':s.desired?'暂停实时跳过':'开启实时跳过';
  button.disabled=toggling||['STARTING','PAUSING','CLEANING'].includes(s.phase);
  text('grant-state',s.phase==='UNAVAILABLE'?'授权状态暂不可用':s.permission?'已授权':'待授权');
  const result=s.lastResult?.split(' · ');
  text('last-result',result?.length===4?result[3]+' · '+result[1]:s.lastResult||'暂无动作记录');
  document.querySelectorAll('[data-rules]').forEach(el=>el.textContent=s.rules||'尚未导入资料包');
  syncButtons();
}
function syncButtons(){
  const busy=pending.has('rules');
  for(const name of ['pick','share','save']){
    const btn=document.querySelector('[data-act="'+name+'"]');
    btn.disabled=busy||(name!=='pick'&&!state?.canExport);
  }
  document.querySelector('[data-act="update"]').disabled=updatePending;
}
function preview(){
  render({phase:!previewPermission?'PERMISSION':previewRunning?'RUNNING':'PAUSED',label:!previewPermission?'待授权':previewRunning?'运行中':'已暂停',
    permission:previewPermission,desired:previewRunning,rules:localRules?.description,canExport:!!localRules});
}
function send(action){if(port){port.postMessage(action);return true;}notice('正在连接，请稍后重试。');return false;}
function authorize(){
  if(state?.permission){settings();return;}
  showDialog('开启实时跳过','Android 会打开无障碍设置。请找到「Zadix · 实时跳过」，授权后返回。\n\n此权限用于识别开屏广告控件和执行跳过。',native?'打开系统设置':'模拟授权成功',()=>{
    if(native){if(!send('toggle'))return;toggling=true;if(state)render(state);}
    else{previewPermission=true;previewRunning=true;preview();notice('本地验收：已模拟授权并开启。');}
  });
}
function settings(){
  if(native){send('settings');return;}
  showDialog('无障碍设置','本地验收只能模拟授权状态。Android 系统授权请在安装后操作。',previewPermission?'模拟撤销授权':'模拟授权成功',()=>{
    previewPermission=!previewPermission;previewRunning=previewPermission;preview();notice('本地验收：授权状态已更新。');
  });
}
function download(blob, name='Zadix.zadixsource'){
  const url=URL.createObjectURL(blob), a=document.createElement('a');
  a.href=url;a.download=name;document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),3000);
}
function ruleBusy(busy){busy?pending.add('rules'):pending.delete('rules');syncButtons();}
function ruleAction(action){
  if(pending.has('rules'))return;
  if(action==='pick'){
    if(native){if(send(action)){ruleBusy(true);feedback('rules-message','请选择文件。');}}
    else byId('files').click();
    return;
  }
  if(!state?.canExport)return;
  showDialog(action==='share'?'分享资料包':'保存资料包','资料包包含规则、启用状态及排除项，可在 Zadix 中重新导入。仅在规则允许转发且你愿意分享这些设置时继续。',action==='share'?'继续分享':'选择保存位置',()=>{
    if(native){if(send(action)){ruleBusy(true);feedback('rules-message','正在准备规则包…');}}
    else {download(localRules.archive);feedback('rules-message','已发起资料包下载，可重新导入。');}
  });
}
const stable=value=>typeof value==='string'&&/^v?\d{1,6}\.\d{1,6}\.\d{1,6}$/.test(value)?value.replace(/^v/,'').split('.').map(Number):null;
function compare(a,b){for(let i=0;i<3;i++)if(a[i]!==b[i])return a[i]-b[i];return 0;}
function publishedBuild(release){
  const version=release.tag_name.replace(/^v/,'');
  let latest=0;
  for(const asset of Array.isArray(release.assets)?release.assets:[]){
    if(!asset||asset.state!=='uploaded'||typeof asset.name!=='string')continue;
    const match=/^Zadix-([0-9.]+)-build([1-9][0-9]{0,9})\.apk$/.exec(asset.name);
    if(match&&match[1]===version&&Number(match[2])<=2100000000)latest=Math.max(latest,Number(match[2]));
  }
  return latest;
}
function updateResult(releases,error){
  updatePending=false;syncButtons();releaseTag=undefined;
  if(error||!Array.isArray(releases)){feedback('update-message',error||'更新来源返回了无法识别的数据。',true);return;}
  const versions=releases.filter(r=>r&&!r.draft&&!r.prerelease&&stable(r.tag_name));
  versions.sort((a,b)=>compare(stable(b.tag_name),stable(a.tag_name)));
  const latest=versions[0];
  if(!latest){feedback('update-message','目前没有已发布的正式版本。');return;}
  const installed=stable(currentVersion);
  if(!installed){feedback('update-message','当前版本暂不可用，请稍后重试。',true);return;}
  const build=publishedBuild(latest);
  const installedBuild=Number(currentBuild);
  const versionOrder=compare(stable(latest.tag_name),installed);
  const canCompareBuild=build>0&&Number.isInteger(installedBuild)&&installedBuild>0;
  const newer=canCompareBuild?build>installedBuild:versionOrder>0&&build===0;
  const unknown=!canCompareBuild&&!newer;
  if(!newer&&!unknown){feedback('update-message','当前已是最新版本。');return;}
  releaseTag=latest.tag_name;
  const label=latest.tag_name+(build?' · Build '+build:'');
  feedback('update-message',newer?'发现更新 '+label+'。':'已找到 '+label+' 的发布记录，暂无法比较构建号。');
  const link=document.createElement('button');link.type='button';link.className='quiet';link.textContent='查看发布 ↗';link.addEventListener('click',openReleases);
  byId('update-message').append(' ',link);
}
function checkUpdate(){
  if(updatePending)return;
  updatePending=true;syncButtons();feedback('update-message','正在检查更新…');
  if(native){if(!send('update'))updateResult(null,'连接尚未建立，请重试。');return;}
  fetch(releaseApi,{signal:AbortSignal.timeout(9000),headers:{Accept:'application/vnd.github+json'}})
    .then(res=>{if(!res.ok)throw new Error(res.status===403||res.status===429?'GitHub 暂时限制了查询，请稍后重试。':'暂时无法读取发布记录。');return res.json();})
    .then(list=>updateResult(list),error=>updateResult(null,error.name==='TimeoutError'?'检查超时，请重试。':error.message||'检查更新失败。'));
}
function openReleases(){
  if(native){send(releaseTag?'release:'+releaseTag:'releases');return;}
  window.open(releaseTag?releaseHome+'/tag/'+encodeURIComponent(releaseTag):releaseHome,'_blank','noopener,noreferrer');
}
byId('files').addEventListener('change',async event=>{
  const files=[...event.target.files];event.target.value='';
  if(!files.length||pending.has('rules'))return;
  ruleBusy(true);feedback('rules-message','正在检查所选文件…');
  try{localRules=await RuleFiles.fromFile(files[0]);feedback('rules-message','已读取资料包 · '+localRules.description);}
  catch(error){feedback('rules-message',error.message||'无法读取所选文件。',true);}
  finally{ruleBusy(false);preview();}
});
byId('live-action').addEventListener('click',()=>{
  if(toggling)return;
  if(state?.phase==='UNAVAILABLE'){send('refresh');return;}
  if(!state?.permission){authorize();return;}
  if(native){if(send('toggle')){toggling=true;render(state);}}
  else{previewRunning=!previewRunning;preview();}
});
document.querySelectorAll('[data-act]').forEach(el=>el.addEventListener('click',async()=>{
  const action=el.dataset.act;
  switch(action){
    case 'authorize': authorize();break;
    case 'settings': settings();break;
    case 'tile': if(native)send('tile');else{notice(previewTile?'本地验收：已模拟添加磁贴。':'本地验收：模拟添加「实时跳过」磁贴。');previewTile=true;}break;
    case 'cleanup': if(native)send('cleanup');else notice('系统清理在 Android 上打开。本地验收无设备连接。');break;
    case 'update': checkUpdate();break;
    case 'releases': openReleases();break;
    case 'example': download(RuleFiles.example(),'Zadix-Example.zadixsource');break;
    default: ruleAction(action);
  }
}));
window.addEventListener('hashchange',route);
window.addEventListener('message',event=>{
  if(!native||port||event.data!=='zadix-ready'||event.ports.length!==1)return;
  port=event.ports[0];
  port.onmessage=reply=>{
    let data;
    try{data=JSON.parse(reply.data);}catch{return;}
    if(data.type==='state'){
      currentVersion=data.version||currentVersion;currentBuild=data.build||currentBuild;
      if(data.rulesBusy)pending.add('rules');
      version();render(data);
    }
    if(data.type==='toggle'){toggling=false;if(state)render(state);if(data.message)notice(data.message);}
    if(data.type==='rules'){ruleBusy(false);feedback('rules-message',data.message,data.error);}
    if(data.type==='notice')notice(data.message);
    if(data.type==='update')updateResult(data.releases,data.error);
    if(data.type==='catalog')catalogResult(data);
    if(data.type==='opening')openingResult(data);
    if(data.type==='catalog-refresh'&&catalog)openCatalog(catalog.category);
    if(data.type==='back')zadixBack();
  };
  port.start();send('refresh');
  if(catalog)openCatalog(catalog.category);
});
version();route();
if(!native){
  byId('mode').hidden=false;byId('example').hidden=false;preview();
  fetch('release.json').then(response=>response.ok?response.json():Promise.reject())
    .then(config=>{
      if(!stable(config.version)||!Number.isSafeInteger(config.build)||!/^[\w-]+\/[\w.-]+$/.test(config.repository))return;
      currentVersion=config.version;currentBuild=config.build;
      releaseHome='https://github.com/'+config.repository+'/releases';
      releaseApi='https://api.github.com/repos/'+config.repository+'/releases?per_page=20';
      version();
    }).catch(()=>{});
}else setTimeout(()=>{if(!state)render({phase:'UNAVAILABLE',label:'无法连接'});},4000);
