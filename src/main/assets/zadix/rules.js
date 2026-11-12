'use strict';
const RuleFiles = (() => {
  const limit = 16 * 1024 * 1024;
  const utf = new TextEncoder();
  const dec = new TextDecoder('utf-8', {fatal:true});
  const fail = message => { throw new Error(message); };
  const crc = bytes => {
    let value = -1;
    for (const byte of bytes) {
      value ^= byte;
      for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    }
    return (value ^ -1) >>> 0;
  };
  function write(entries) {
    const parts = [], directory = [];
    let offset = 0, size = 0;
    for (const [name, data] of entries) {
      const path = utf.encode(name), checksum = crc(data);
      const header = new Uint8Array(30 + path.length);
      const view = new DataView(header.buffer);
      view.setUint32(0,0x04034b50,true); view.setUint16(4,20,true);
      view.setUint32(14,checksum,true); view.setUint32(18,data.length,true); view.setUint32(22,data.length,true);
      view.setUint16(26,path.length,true); header.set(path,30);
      const central = new Uint8Array(46 + path.length);
      const cv = new DataView(central.buffer);
      cv.setUint32(0,0x02014b50,true); cv.setUint16(4,20,true); cv.setUint16(6,20,true);
      cv.setUint32(16,checksum,true); cv.setUint32(20,data.length,true); cv.setUint32(24,data.length,true);
      cv.setUint16(28,path.length,true); cv.setUint32(42,offset,true); central.set(path,46);
      parts.push(header,data); directory.push(central); offset += header.length + data.length; size += central.length;
    }
    const tail = new Uint8Array(22), tv = new DataView(tail.buffer);
    tv.setUint32(0,0x06054b50,true); tv.setUint16(8,entries.size,true); tv.setUint16(10,entries.size,true);
    tv.setUint32(12,size,true); tv.setUint32(16,offset,true);
    return new Blob([...parts,...directory,tail],{type:'application/octet-stream'});
  }
  async function unzip(bytes) {
    const result = new Map(), view = new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
    let end = bytes.length - 22;
    while (end >= 0 && end >= bytes.length - 65557 && view.getUint32(end,true) !== 0x06054b50) end--;
    if (end < 0 || end < bytes.length - 65557) fail('ZIP 目录无法读取。');
    let pos = view.getUint32(end+16,true), total = 0;
    const count = view.getUint16(end+10,true);
    if (count < 1 || count > 128) fail('ZIP 文件条目数无效。');
    for(let i=0;i<count;i++){
      if (pos+46>end || view.getUint32(pos,true)!==0x02014b50) fail('ZIP 目录损坏。');
      const method=view.getUint16(pos+10,true), checksum=view.getUint32(pos+16,true);
      const compressed=view.getUint32(pos+20,true), size=view.getUint32(pos+24,true);
      const length=view.getUint16(pos+28,true), extra=view.getUint16(pos+30,true), comment=view.getUint16(pos+32,true);
      const local=view.getUint32(pos+42,true);
      const path=dec.decode(bytes.subarray(pos+46,pos+46+length));
      pos+=46+length+extra+comment;
      total+=size;
      if(total>limit || result.has(path) || local+30>bytes.length || view.getUint32(local,true)!==0x04034b50) fail('ZIP 超出限制或包含重复条目。');
      const from=local+30+view.getUint16(local+26,true)+view.getUint16(local+28,true);
      if(from+compressed>bytes.length) fail('ZIP 条目长度不正确。');
      let data=bytes.subarray(from,from+compressed);
      if(method===8){
        const reader=new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
        const output=new Uint8Array(size);
        let offset=0;
        try {
          while(true){
            const {value,done}=await reader.read();
            if(done)break;
            if(offset+value.length>size)fail('ZIP 解压长度不正确。');
            output.set(value,offset); offset+=value.length;
          }
          if(offset!==size)fail('ZIP 解压长度不正确。');
          data=output;
        }finally{await reader.cancel();}
      } else if(method!==0) fail('不支持此 ZIP 压缩方式。');
      if(data.length!==size || crc(data)!==checksum) fail('ZIP 文件校验失败。');
      result.set(path,data);
    }
    return result;
  }
  const profileFields={
    sources:['id','order','enable'],
    groups:['subsItemId','type','appId','groupKey','enable','exclude'],
    categories:['subsItemId','categoryKey','enable']
  };
  const sameKeys=(object,keys)=>object && Object.keys(object).length===keys.length && keys.every(key=>Object.hasOwn(object,key));
  const json=bytes=>JSON.parse(dec.decode(bytes));
  function profile(value) {
    if(!sameKeys(value,Object.keys(profileFields))) fail('资料包配置包含未知字段。');
    const integer=Number.isSafeInteger;
    const bool=x=>x==null || typeof x==='boolean';
    const string=x=>x==null || typeof x==='string';
    const valid={
      sources:r=>integer(r.id)&&integer(r.order)&&bool(r.enable),
      groups:r=>integer(r.subsItemId)&&integer(r.type)&&string(r.appId)&&(r.groupKey===undefined||integer(r.groupKey))&&bool(r.enable)&&string(r.exclude),
      categories:r=>integer(r.subsItemId)&&integer(r.categoryKey)&&bool(r.enable)
    };
    for(const [key,fields] of Object.entries(profileFields)){
      if(!Array.isArray(value[key]) || value[key].some(row=>!row||Array.isArray(row)||
        typeof row!=='object'||Object.keys(row).some(field=>!fields.includes(field))||!valid[key](row)))fail('资料包启用配置无效。');
    }
    return value;
  }
  function sourceMeta(bytes) {
    const text=dec.decode(bytes).replace(/^\uFEFF/,'');
    let value;
    try{value=JSON.parse(text);}catch{
      // The preview reads summary metadata; Android also parses and validates the complete rules.
      const head=text.slice(0,4096).replace(/^\s*(?:(?:\/\/[^\n]*\n|\/\*[\s\S]*?\*\/)\s*)*/,'');
      const field=key=>new RegExp('[{,]\\s*["\']?'+key+'["\']?\\s*:\\s*(-?\\d+)\\b').exec(head)?.[1];
      if(!head.startsWith('{')||!text.trim().endsWith('}')||field('id')===undefined||field('version')===undefined)
        fail('规则文件无法识别。');
      value={id:Number(field('id')),version:Number(field('version'))};
    }
    if(!Number.isSafeInteger(value?.id)||!Number.isSafeInteger(value.version)) fail('规则文件缺少编号或版本。');
    return value;
  }
  function label({id,version}) {
    return (id===825?'Adpro':id===666?'AIsouler':id<0?'本地规则 #'+(-id):'资料来源 #'+id)+' v'+version;
  }
  async function fromFile(file) {
    if(!file?.name.toLowerCase().endsWith('.zadixsource')||!file.size||file.size>limit)
      fail('请选择一个 .zadixsource 文件（最大 16 MiB）。');
    const bytes=new Uint8Array(await file.arrayBuffer());
    if(bytes[0]!==80||bytes[1]!==75||bytes[2]!==3||bytes[3]!==4)fail('资料包格式无效。');
    const entries=await unzip(bytes);
    if(!entries.has('manifest.json')||!entries.has('profile.json'))fail('请选择有效的 .zadixsource 资料包。');
    const header=json(entries.get('manifest.json'));
    if(!sameKeys(header,['format','version'])||header.format!=='zadixsource'||header.version!==1)
      fail('此版本无法读取该资料包格式。');
    const settings=profile(json(entries.get('profile.json')));
    const metadata=[];
    for(let i=1;i<=entries.size-2;i++){
      const source=entries.get('rules/'+i+'.json5');
      if(!source?.length)fail('资料包包含未知条目或缺少规则文件。');
      metadata.push(sourceMeta(source));
    }
    const byId=new Map(metadata.map(m=>[m.id,m]));
    if(byId.size!==metadata.length||new Set(settings.sources.map(row=>row.id)).size!==settings.sources.length)
      fail('资料来源编号重复。');
    const selected=settings.sources.filter(row=>row.enable!==false).sort((a,b)=>a.order-b.order);
    if(!selected.length)fail('资料包没有启用的规则来源。');
    const description=selected.map(row=>{
      const meta=byId.get(row.id);
      if(!meta)fail('资料包缺少已启用的规则来源 '+row.id+'。');
      return label(meta);
    }).join('、');
    return {description,archive:new Blob([bytes],{type:'application/octet-stream'})};
  }
  function example() {
    return write(new Map([
      ['manifest.json',utf.encode('{"format":"zadixsource","version":1}')],
      ['profile.json',utf.encode('{"sources":[{"id":501,"order":0,"enable":true}],"groups":[],"categories":[]}')],
      ['rules/1.json5',utf.encode(JSON.stringify({id:501,name:'验收示例规则',version:1,apps:[{
        id:'com.ahuamiao.adprotection.fixture',groups:[{key:0,name:'开屏广告',
          activityIds:['.FixtureActivity'],rules:[{key:0,action:'clickNode',
            matches:['[id="com.ahuamiao.adprotection.fixture:id/ad_marker"]','[id="com.ahuamiao.adprotection.fixture:id/ad_close"]'],
            excludeMatches:['[id="com.ahuamiao.adprotection.fixture:id/sensitive_marker"]']}]}]
      }]}))]
    ]));
  }
  return {fromFile,example};
})();
