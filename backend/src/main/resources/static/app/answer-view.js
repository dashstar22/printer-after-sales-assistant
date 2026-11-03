import {serviceApi as api} from './api.js';
import {el,button,field,pairs,link,feedback} from './ui.js';

const names={KNOWLEDGE_UNAVAILABLE:'资料查询暂不可用',MODEL_CALL_LIMIT:'本轮辅助回答次数已达上限',TOOL_CALL_LIMIT:'本轮资料操作次数已达上限',ASSISTANT_BUSY:'服务正忙，请稍后再试',ASSISTANT_CANCELLED:'本轮已停止，可查询记录或在线报修',ANSWERED:'资料回复 · 请核对适用条件',NO_EVIDENCE:'资料未覆盖，暂不能确认',NEEDS_INPUT:'请补充信息',EVIDENCE_NOT_AUTHORIZED:'资料暂未获准用于回答',DEGRADED:'部分查询不可用 · 以下为降级原文',EXCERPT_ONLY:'仅提供原文 · 暂未形成处理建议',RETRIEVAL_UNAVAILABLE:'资料查询暂不可用',REFERENCE_REJECTED:'回复依据未能确认',MODEL_UNAVAILABLE:'辅助回答暂不可用',MODEL_TIMEOUT:'辅助回答等待超时',KNOWLEDGE_TIMEOUT:'资料查询超时',TURN_TIMEOUT:'本轮等待超时'};
const routeNames={RULE_TOPIC_DIRECT:'按规则主题查询',HYBRID:'综合资料查询',HYBRID_RERANK:'综合资料查询'};
const pathNames={OK:'本次完成',CACHED:'复用已核对候选',UNAVAILABLE:'本次不可用',NOT_RUN:'本次未执行',SKIPPED:'本次未执行',DISABLED:'未启用',FAILED:'本次失败',HIT:'复用候选',MISS:'本次未复用',BYPASS:'本次未使用缓存'};
const fallbackStatuses=new Set(['KNOWLEDGE_UNAVAILABLE','MODEL_CALL_LIMIT','TOOL_CALL_LIMIT','ASSISTANT_BUSY','ASSISTANT_CANCELLED','NO_EVIDENCE','EVIDENCE_NOT_AUTHORIZED','DEGRADED','EXCERPT_ONLY','RETRIEVAL_UNAVAILABLE','REFERENCE_REJECTED','MODEL_UNAVAILABLE','MODEL_TIMEOUT','KNOWLEDGE_TIMEOUT','TURN_TIMEOUT']);
export function answerStatus(reply){return names[reply?.status]||null;}
export function mayReport(reply){return fallbackStatuses.has(reply?.status);}
export function sourceView(source,index,prefix){
  const ref=el('details',null,'citation');ref.id=prefix+'-source-'+index;
  const type=source.simulated?'模拟资料':source.sourceType==='enterprise_policy'?'企业规则':source.sourceType==='enterprise_faq'?'企业 FAQ':source.sourceType==='official_manual'?'厂商资料':source.selfAuthored?'自编资料':'来源资料';
  ref.append(el('summary',`[${index}] ${source.title} · ${type}`));
  ref.append(pairs([['型号',source.modelScope==='GENERAL'?'通用资料':(source.models||[]).join('、')],['版本',source.version],['定位',source.pageNumber?'第 '+source.pageNumber+' 页':source.section],['片段标识',source.pointId],['来源',source.source],['使用条件',source.useCondition]]),el('p',source.content,'source-content'));
  try{const url=new URL(source.sourceUrl);if(url.protocol==='https:'){const a=link('查看来源网页',url.href);a.target='_blank';a.rel='noopener noreferrer';ref.append(a);}}catch{}
  return ref;
}
export function answerDetails(reply,messageId){
  const host=el('div',null,'answer-details'),prefix='answer-'+messageId;
  const citations=Array.isArray(reply.citations)?reply.citations:[];
  if(Array.isArray(reply.points)&&reply.points.length){const points=el('ol',null,'answer-points');points.setAttribute('aria-label','回复要点与引用');
    for(const point of reply.points){const item=el('li');item.append(el('span',point.text));for(const number of point.citations||[]){if(!Number.isInteger(number)||number<1||number>citations.length)continue;const a=link('['+number+']','#'+prefix+'-source-'+number,'answer-ref');a.setAttribute('aria-label','展开第 '+number+' 条原文依据');a.addEventListener('click',e=>{e.preventDefault();const target=document.getElementById(prefix+'-source-'+number);if(target){target.open=true;target.querySelector('summary').focus();target.scrollIntoView({block:'nearest'});}});item.append(a);}points.append(item);}const fold=el('details');fold.append(el('summary','查看要点对应的原文'),points);host.append(fold);
  }
  citations.forEach((source,index)=>host.append(sourceView(source,index+1,prefix)));
  if(reply.retrieval&&Object.keys(reply.retrieval).length){const t=reply.retrieval,detail=el('details',null,'answer-method');detail.append(el('summary','查看本次资料查询方式'));
    const values=[];if(t.method)values.push(['查询方式',routeNames[t.method]||'服务端记录的资料查询']);
    for(const [k,label]of [['vector','语义匹配'],['bm25','关键词匹配'],['rerank','相关性排序'],['cache','候选复用']])if(t[k]!==undefined)values.push([label,pathNames[t[k]]||'状态待核对']);
    if(values.length)detail.append(pairs(values));else detail.append(el('p',t.status==='NOT_RUN'?'本轮没有执行资料查询。':'本轮已记录查询结果，未提供细分路径。'));
    if(t.supportVerification==='HUMAN_REVIEW_REQUIRED')detail.append(el('p','引用可供核对；资料是否完整支持实际处理方案仍需人工判断。','subtle'));host.append(detail);
  }
  if(reply.query?.reason==='PREVIOUS_KNOWLEDGE_TURN')host.append(el('p','本次按当前设备的上一轮资料话题补全了指代；你的原始问题保持不变。','subtle'));
  else if(reply.query?.reason==='AMBIGUOUS_REFERENCE')host.append(el('p','尚不能确定指代内容，请补充具体型号或问题。','subtle'));
  return host;
}

export function createAnswerFeedback({userId,isActive,onAuthError}){
  const records=new Map(),drafts=new Map(),loading=new Set(),painters=new Map();let disposed=false;
  const key=id=>'printer.service.answer-feedback.v1.'+userId+'.'+id;
  function read(id){try{return JSON.parse(sessionStorage.getItem(key(id))||'null');}catch{return null;}}
  function save(id,value){try{value?sessionStorage.setItem(key(id),JSON.stringify(value)):sessionStorage.removeItem(key(id));}catch{}}
  function valid(reply){return ['MODEL_API','SERVICE_GUIDANCE'].includes(reply?.mode)&&!['RESULT_UNKNOWN','AI_DISABLED','WRITE_RESULT_UNKNOWN','PROCESSING'].includes(reply?.status);}
  function path(conversation,id){return '/api/conversations/'+encodeURIComponent(conversation)+'/messages/'+id+'/feedback';}
  function panel(conversation,message){
    if(!valid(message.response))return null;const id=message.id,host=el('section',null,'answer-feedback');host.dataset.messageId=id;
    let draft=drafts.get(id)||{rating:'UNRESOLVED',reason:'',pending:null,note:'',edited:false};drafts.set(id,draft);
    const stored=read(id);if(stored?.rating&&['HELPFUL','UNRESOLVED'].includes(stored.rating)&&typeof stored.reason==='string'&&stored.reason.length<=1000&&!draft.pending)draft={...draft,...stored,pending:{rating:stored.rating,reason:stored.reason}},drafts.set(id,draft);
    function paint(){if(disposed||!isActive()||!host.isConnected)return;host.replaceChildren();host.append(el('h4','这条回复解决了问题吗？'));
      const current=records.get(id);host.append(el('p',current?.rating?'已保存：'+(current.rating==='HELPFUL'?'有帮助':'仍未解决'):'反馈只用于改进资料，不会自动发布 FAQ。','subtle'));
      if(draft.note){const note=el('p',draft.note,'inline-notice');note.setAttribute('role','status');host.append(note);}
      const f=el('form');const rating=el('select');rating.id='answer-rating-'+id;rating.append(new Option('有帮助','HELPFUL'),new Option('仍未解决','UNRESOLVED'));rating.value=draft.rating;rating.addEventListener('change',()=>{draft.rating=rating.value;draft.edited=true;});
      const reason=el('input');reason.id='answer-reason-'+id;reason.maxLength=1000;reason.value=draft.reason;reason.autocomplete='off';reason.addEventListener('input',()=>{draft.reason=reason.value;draft.edited=true;});
      f.append(field('反馈结果',rating),field('原因（可选，最多 500 字，请勿填写联系方式）',reason));const submit=el('button',draft.pending?'使用原内容重试反馈':'保存这条反馈','secondary-button');submit.type='submit';submit.disabled=loading.has(id);f.append(submit);
      f.addEventListener('submit',async e=>{e.preventDefault();if(loading.has(id))return;const body=draft.pending||{rating:draft.rating,reason:draft.reason.trim()};if([...body.reason].length>500||/[\u0000-\u001f\u007f-\u009f]/u.test(body.reason)){draft.note='原因最多 500 字，不能包含换行或控制字符。';paint();return;}
        draft.pending=body;save(id,body);loading.add(id);paint();try{const value=await api.write(path(conversation,id),body,'PUT');if(disposed||!isActive())return;records.set(id,value);draft.pending=null;save(id,null);draft.note='反馈已保存，修改会更新同一条记录。';}catch(error){if(disposed||!isActive())return;if(error.status===401||error.status===403){onAuthError(error);return;}draft.note=feedback(error)+' 请先刷新核对原反馈，或用原内容重试。';}finally{loading.delete(id);painters.get(id)?.();}
      });if(draft.pending){rating.disabled=true;reason.disabled=true;host.append(el('p','原反馈结果待核对，保持原内容；刷新不会自动提交。','subtle'));}host.append(f);
    }
    painters.set(id,paint);queueMicrotask(paint);
    if(!loading.has(id)&&!records.has(id)){loading.add(id);api.get(path(conversation,id)).then(value=>{if(disposed||!isActive())return;records.set(id,value);if(!draft.pending&&!draft.edited&&value.rating){draft.rating=value.rating;draft.reason=value.reason||'';}if(draft.pending&&value.rating===draft.pending.rating&&(value.reason||'')===draft.pending.reason){draft.pending=null;save(id,null);draft.note='已查回原反馈。';}}).catch(error=>{if(disposed||!isActive())return;if(error.status===401||error.status===403)onAuthError(error);else draft.note=feedback(error);}).finally(()=>{loading.delete(id);painters.get(id)?.();});}
    return host;
  }
  return {panel,refresh(){records.clear();},dispose(){disposed=true;records.clear();drafts.clear();painters.clear();}};
}
