/* WContract JCEF view. All endpoint, graph, comparison and evaluation results come from Java. */
(() => {
  "use strict";
  const $=id=>document.getElementById(id);
  const state={endpoint:null,methods:[],selectedKey:"",pinned:false,view:"catalog",
    display:"request",sources:{curl:null,collection:null},source:"curl",collectionRaw:"",
    collectionName:"",connections:[],targets:[],report:null,history:[],running:false,
    activeRisk:null,progress:0,scope:"isolated",bindingName:"contrato.json"};
  const esc=x=>String(x??"").replace(/&/g,"&amp;").replace(/</g,"&lt;")
    .replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;");
  const send=(type,args={})=>{if(typeof window.__jsonLensNative==="function")
    window.__jsonLensNative({type,...args});};
  const toggle=(node,show)=>{if(node)node.hidden=!show;};
  const p=(id,text)=>{if($(id))$(id).textContent=String(text??"");};
  let toastTimer;
  const toast=message=>{p("toast",message);$("toast").classList.remove("hidden");
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>$("toast").classList.add("hidden"),3000);};
  const visible=(view)=>{state.view=view;
    document.querySelectorAll(".tab[data-view]").forEach(t=>t.classList.toggle("active",t.dataset.view===view));
    document.querySelectorAll(".panel").forEach(t=>t.classList.toggle("active",t.id===view));
    document.querySelector(".main-scroll").scrollTop=0;};
  const card=(title,sub,extra="")=>{const box=document.createElement("div");box.className="card";
    box.innerHTML="<div class='card-title'>"+esc(title)+"</div><p class='sub'>"+esc(sub)+"</p>"+extra;
    return box;};
  const detailJSON=value=>value===undefined?"// Contrato não disponível":JSON.stringify(value,null,2);
  const sendCopy=text=>{if(!text)return toast("Nenhum conteúdo disponível para copiar.");
    send("copy",{text});toast("Texto copiado para a área de transferência.");};
  const endpointKey=e=>e?.key||"";
  function renderCatalog(){
    const holder=$("catalogList");holder.replaceChildren();
    const methods=state.methods||[];
    const selected=state.endpoint;
    p("catalogClass",selected?.title?.split(".")[0]||"Nenhum endpoint selecionado");
    p("catalogNote",methods.length+" endpoint(s) reconhecido(s) nesta classe.");
    p("pinBtn",state.pinned?"Desafixar seleção":"Fixar seleção");
    for(const method of methods){
      const node=document.createElement("div");node.className="endpoint-entry";
      if(method.key===state.selectedKey)node.classList.add("current");
      const words=method.label.split(" ");
      const verb=words[0]||"ANY";
      node.innerHTML="<div class='spread'><span class='method'>"+esc(verb)+"</span><span class='pill'>"+
        (method.key===state.selectedKey?"SELECIONADO":"DECLARADO")+"</span></div>"+
        "<div class='path'>"+esc(method.label)+"</div><div class='row' style='margin-top:10px'>"+
        "<button class='btn small primary' data-action='contract'>Expandir contrato</button>"+
        "<button class='btn small' data-action='integrations'>Ver integrações</button></div>";
      node.querySelector('[data-action="contract"]').addEventListener("click",()=>{
        send("select",{key:method.key});visible("contract");
      });
      node.querySelector('[data-action="integrations"]').addEventListener("click",()=>{
        send("select",{key:method.key});visible("integrations");
        send("integrations");
      });
      holder.appendChild(node);
    }
    if(!methods.length)holder.appendChild(card("Nenhum endpoint reconhecido",
      "Abra a classe de um @RestController ou @FeignClient, ou use o menu de contexto no editor."));
  }
  function renderContract(){
    const e=state.endpoint;
    p("contractRoute",e?.path||"Selecione um endpoint");
    p("contractTitle",e?.title||"Request e Response inferidos do Java");
    p("httpMethod",e?.verb||"—");
    p("directionTag",e?.direction||"—");
    p("liveStatus",e?"● Java PSI ativo":"● Aguardando editor Java");
    p("contractWarnings",(e?.warnings||[]).slice(0,2).join(" · ")||
      "Dados ilustrativos inferidos do Java; a aplicação não é executada.");
    document.querySelectorAll("[data-schema]").forEach(b=>
      b.classList.toggle("active",b.dataset.schema===state.display));
    const schema=state.display.endsWith("Schema");
    const field=state.display.startsWith("response")?"response":"request";
    const json=e?.[state.display];
    p("jsonBody",detailJSON(json));
    p("curlPreview",e?.curl||"// Selecione um endpoint.");
    const rows=$("fieldsBody");rows.replaceChildren();
    const contract=e?.[field+"Schema"];
    for(const [name,value] of Object.entries(contract?.properties||{})){
      const tr=document.createElement("tr");
      tr.innerHTML="<td>"+esc(name)+"</td><td>"+esc((value?.type||"unknown")+
        (value?.format?" · "+value.format:""))+"</td><td>"+
        ((contract.required||[]).includes(name)?"<span class='required'>Sim</span>":"<span class='optional'>Não</span>")+
        "</td>";
      rows.appendChild(tr);
    }
    if(!rows.children.length){
      const tr=document.createElement("tr");tr.innerHTML="<td colspan='3'>Sem campos de objeto declarados.</td>";
      rows.appendChild(tr);
    }
    const notes=$("parametersBody");notes.replaceChildren();
    const parts=e?.notes||[];
    if(!parts.length)notes.textContent="Nenhum parâmetro path/query/header declarado.";
    for(const note of parts){
      const d=document.createElement("div");d.className="kv";d.textContent=note;notes.appendChild(d);
    }
  }
  function renderIntegrations(){
    const cards=$("integrationList");cards.replaceChildren();
    const graph=state.connections||[];
    p("integrationHeading",state.endpoint?.title||"Nenhum endpoint selecionado");
    p("integrationCount",graph.length+" conexão(ões)");
    const note=graph.length?"Conexões retornadas pela análise PSI. O vínculo não prova tráfego em runtime.":
      "Nenhuma conexão resolvida. Isso não comprova ausência de integrações.";
    p("integrationNote",note);
    for(const connection of graph){
      const mode=connection.kind==="FEIGN_HTTP"?"Cliente Feign":
        connection.kind==="HTTP_NAO_RESOLVIDO"?"HTTP desconhecido":"Chamada Java";
      const c=document.createElement("div");c.className="node";
      c.innerHTML="<div class='spread'><b>"+esc(connection.destination)+"</b>"+
        "<span class='pill "+(connection.kind==="FEIGN_HTTP"?"green":"warn")+"'>"+
        esc(mode)+"</span></div><small>"+esc(connection.detail)+"</small>"+
        "<div class='actions' style='margin-top:8px'><button class='btn small' data-action='code'>Ir ao código</button>"+
        (connection.toKey?"<button class='btn small ghost' data-action='target'>Vincular contrato</button>":"")+"</div>";
      c.querySelector('[data-action="code"]').addEventListener("click",()=>
        send("open-location",{path:connection.filePath,offset:connection.offset}));
      c.querySelector('[data-action="target"]')?.addEventListener("click",()=>{
        $("contractTarget").value=connection.toKey;
        $("contractText").focus();});
      cards.appendChild(c);
    }
    if(!graph.length)cards.appendChild(card("Nenhuma chamada confirmada",
      "A análise limitada de fluxo de dados não identificou um Feign ou endpoint relacionado."));
  }
  function renderTargets(){
    const sel=$("contractTarget");
    const current=sel.value;
    sel.replaceChildren();
    const entries=state.targets||[];
    const all=[...(state.endpoint?[{key:state.endpoint.key,title:state.endpoint.title,
      verb:state.endpoint.verb,path:state.endpoint.path}]:[]),...entries];
    const seen=new Set();
    for(const ep of all){
      if(!ep.key||seen.has(ep.key))continue;seen.add(ep.key);
      const option=document.createElement("option");
      option.value=ep.key;
      option.textContent=[ep.verb,ep.path,ep.title].filter(Boolean).join(" · ");
      sel.appendChild(option);
    }
    if([...sel.options].some(o=>o.value===current))sel.value=current;
  }
  function renderRisks(){
    const summary=state.report?.summary;
    const stats=$("riskStats");stats.replaceChildren();
    for(const [title,value] of [["Erros",summary?.errors??"—"],
       ["Suspeitas",summary?.suspected??"—"],["Não resolvidos",summary?.unresolved??"—"]]){
      const d=document.createElement("div");d.className="stat";
      d.innerHTML="<small>"+esc(title)+"</small><strong>"+esc(value)+"</strong>";
      stats.appendChild(d);
    }
    const host=$("riskList");host.replaceChildren();
    const risks=state.report?.risks||[];
    if(!risks.length){host.appendChild(card("Nenhuma ocorrência registrada",
      "Execute uma avaliação. Sem contratos conhecidos, o resultado permanece não verificado."));return;}
    for(const risk of risks){
      const element=document.createElement("div");element.className="risk";
      const badge=risk.confidence==="CONFIRMED"?"red":"warn";
      element.innerHTML="<div class='spread'><strong>"+esc(risk.rule)+"</strong>"+
        "<span class='pill "+badge+"'>"+esc(risk.confidence)+"</span></div>"+
        "<p>"+esc(risk.message)+"</p><small>"+esc(risk.endpoint)+" · "+esc(risk.pointer)+"</small>"+
        "<div class='actions' style='margin-top:9px'><button class='btn small' data-open>Ir ao código</button>"+
        "<button class='btn small ghost' data-evidence>Explicação e sugestão</button></div>";
      element.querySelector("[data-open]").addEventListener("click",()=>
        send("open-location",{path:risk.filePath,offset:risk.offset}));
      element.querySelector("[data-evidence]").addEventListener("click",()=>{
        toggle($("riskDetail"),true);p("riskDetailTitle",risk.rule+" · "+risk.severity);
        p("riskDetailText","EVIDÊNCIA\n"+risk.evidence+"\n\nMOTIVO\n"+risk.message+
          "\n\nSUGESTÃO\n"+risk.suggestion+"\n\nGRAU DE CERTEZA\n"+risk.confidence);
        $("riskDetail").scrollIntoView({block:"nearest",behavior:"smooth"});
      });
      host.appendChild(element);
    }
  }
  function renderReport(){
    const r=state.report, summary=r?.summary;
    p("reportSummary",summary?.summary||"Ainda não há relatório nesta sessão.");
    const metrics=$("reportMetrics");metrics.replaceChildren();
    for(const [name,value] of [
      ["Aderência",summary?.compatibility>=0?summary.compatibility+"%":"Não determinada"],
      ["Cobertura",summary?summary.evaluated+"/"+summary.total:"—"],
      ["Ocorrências",summary?(summary.errors+summary.suspected+summary.unresolved):"—"]]){
      const div=document.createElement("div");div.className="stat";
      div.innerHTML="<small>"+esc(name)+"</small><strong style='font-size:16px'>"+esc(value)+"</strong>";
      metrics.appendChild(div);
    }
    const holder=$("endpointReport");holder.replaceChildren();
    for(const item of r?.endpoints||[]){
      const div=document.createElement("div");div.className="kv";
      div.innerHTML="<span>"+esc(item.verb+" "+item.path)+"</span><b>"+
        esc(item.status==="NOT_VERIFIED"?"Não verificado":item.status==="PARTIAL"?"Parcial":
          item.status==="INCOMPATIBLE"?"Incompatível":"Compatível no escopo")+"</b>";
      holder.appendChild(div);
    }
    renderRisks();
  }
  function renderHistory(){
    const host=$("historyList");host.replaceChildren();
    for(const h of state.history||[]){
      const div=document.createElement("div");div.className="kv";
      const score=h.compatibility>=0?h.compatibility+"%":"Não determinada";
      div.innerHTML="<div><b>"+esc(h.scope)+"</b><div class='sub'>"+esc(h.timestamp)+
        " · "+esc(h.evaluated+"/"+h.total)+" endpoints com contratos</div></div>"+
        "<span class='pill'>"+esc(score)+"</span>";
      host.appendChild(div);
    }
    if(!host.children.length)host.appendChild(card("Histórico vazio",
      "A primeira avaliação concluída aparecerá aqui com data, escopo e métricas."));
  }
  function renderSearch(){
    const host=$("searchResults");host.replaceChildren();
    const data=state.sources[state.source];
    p("searchSummary",data?data.scanned+" endpoint(s) verificados · "+data.requests.length+" requisição(ões)":"Nenhuma busca iniciada.");
    if(!data)return;
    for(const req of data.requests){
      const root=card(req.method+" "+req.path,req.name);
      for(const [name,items] of [["Controllers (recebem)",req.inbound||[]],
                                  ["Feign (enviam)",req.outbound||[]]]){
        const title=document.createElement("p");title.className="caption";title.textContent=name;
        root.appendChild(title);
        for(const match of items){
          const div=document.createElement("div");div.className="kv";
          div.innerHTML="<span>"+esc(match.title)+"<br><small>"+esc(match.method+" "+match.path)+
            "</small></span><b>"+esc(match.score)+"/100</b>";
          div.addEventListener("click",()=>send("open",{id:match.id}));
          div.style.cursor="pointer";root.appendChild(div);
        }
      }
      host.appendChild(root);
    }
  }
  function startAssessment(scope){
    if(state.running)return;
    state.scope=scope;
    $("scope").value=scope;
    state.running=true;toggle($("progressShell"),true);
    $("progressTrack").setAttribute("aria-busy","true");
    $("cancelBtn").disabled=false;$("evaluateBtn").disabled=true;
    p("progressPhase","Descobrindo endpoints e integrações…");
    p("progressPercent","—");p("progressDetail","Etapa de indexação: total ainda desconhecido.");
    send("evaluate",{scope});visible("reports");
  }
  function setProgress(data){
    if(data.indeterminate){
      $("progressTrack").setAttribute("aria-busy","true");
      p("progressPercent","—");
    }else{
      $("progressTrack").setAttribute("aria-busy","false");
      const percentage=Math.max(0,Math.min(100,Number(data.percent)||0));
      $("progressBar").style.width=percentage+"%";p("progressPercent",percentage+"%");
    }
    p("progressPhase",data.phase||"Analisando");
    p("progressDetail",data.detail||"");
  }
  function stopAssessment(){
    state.running=false;$("cancelBtn").disabled=true;$("evaluateBtn").disabled=false;
    $("progressTrack").setAttribute("aria-busy","false");
  }
  function renderComparison(data){
    if(data.error)return p("compareResults","⚠ "+data.error);
    const list=(data.findings||[]).filter(x=>x.severity!=="INFO")
      .map(x=>x.severity+" "+x.pointer+" — "+x.message);
    p("compareResults","Aderência heurística: "+data.score+"% · "+
      (list.length?list.join("\n"):"Nenhuma divergência estrutural identificada neste escopo."));
    $("compareResults").style.whiteSpace="pre-wrap";
  }
  function initSplit(){
    const split=$("contractSplit"),handle=split.querySelector(".resize-handle");
    const memory=window.localStorage?.getItem("wcontract:split");
    if(memory){const n=Number(memory);if(n>=20&&n<=80)split.style.setProperty("--first-panel",n+"%");}
    let drag=false;
    const move=(event)=>{
      if(!drag)return;
      const rect=split.getBoundingClientRect();
      const vertical=window.innerWidth<=600;
      const raw=vertical?(event.clientY-rect.top)/rect.height:(event.clientX-rect.left)/rect.width;
      const percentage=Math.round(Math.max(.2,Math.min(.8,raw))*100);
      split.style.setProperty("--first-panel",percentage+"%");
    };
    handle.addEventListener("pointerdown",event=>{
      drag=true;handle.setPointerCapture(event.pointerId);event.preventDefault();
    });
    handle.addEventListener("pointermove",move);
    handle.addEventListener("pointerup",event=>{
      if(!drag)return;move(event);drag=false;
      const percent=parseInt(split.style.getPropertyValue("--first-panel"),10);
      try{localStorage.setItem("wcontract:split",String(percent));}catch(_){}});
    handle.addEventListener("keydown",event=>{
      if(!["ArrowLeft","ArrowRight","ArrowUp","ArrowDown"].includes(event.key))return;
      event.preventDefault();
      const current=parseInt(split.style.getPropertyValue("--first-panel")||"46",10);
      const delta=["ArrowLeft","ArrowUp"].includes(event.key)?-5:5;
      const next=Math.max(20,Math.min(80,current+delta));
      split.style.setProperty("--first-panel",next+"%");
      try{localStorage.setItem("wcontract:split",String(next));}catch(_){}});
  }
  window.JsonLensUI={onNative(event){
    switch(event?.type){
      case "ready":p("liveStatus","● Conectado ao Java");break;
      case "preview":
        state.endpoint=event.endpoint||null;
        state.methods=event.methods||[];
        state.selectedKey=event.selectedKey||"";
        state.pinned=!!event.pinned;
        renderCatalog();renderContract();renderTargets();
        if(event.note && !state.endpoint)p("catalogNote",event.note);
        break;
      case "pin":state.pinned=!!event.pinned;renderCatalog();break;
      case "integrations":
        state.connections=event.connections||[];
        state.targets=event.targets||[];
        renderIntegrations();renderTargets();
        break;
      case "search":state.sources[event.source]=event;
        if(state.source===event.source)renderSearch();break;
      case "scan-error":toast(event.message||"Busca inválida.");p("searchSummary",event.message);break;
      case "comparison":renderComparison(event);break;
      case "contract-saved":toast(event.message||"Contrato associado.");break;
      case "assessment-progress":setProgress(event);break;
      case "assessment-result":
        stopAssessment();state.report=event.report;renderReport();
        p("progressPhase","Avaliação concluída");p("progressPercent","100%");
        $("progressBar").style.width="100%";p("progressDetail","Relatório gerado com evidências.");
        visible("reports");send("history");break;
      case "assessment-cancelled":stopAssessment();p("progressPhase","Avaliação cancelada");
        p("progressDetail","Nenhum relatório parcial registrado.");toast("Avaliação cancelada.");break;
      case "history":state.history=event.history||[];renderHistory();break;
      case "navigate":
        if(event.view==="assess-isolated")startAssessment("isolated");
        else if(event.view==="assess-project")startAssessment("project");
        else if(event.view==="integrations"){visible("integrations");send("integrations");}
        else visible("contract");
        break;
      case "notice":toast(event.message||"Aviso");break;
      case "recompare":break;
      default:break;
    }
  }};
  for(const tab of document.querySelectorAll(".tab[data-view]"))
    tab.addEventListener("click",()=>{visible(tab.dataset.view);
      if(tab.dataset.view==="integrations")send("integrations");
      if(tab.dataset.view==="reports")send("history");});
  $("pinBtn").addEventListener("click",()=>send("pin"));
  $("refreshBtn").addEventListener("click",()=>send("refresh"));
  for(const t of document.querySelectorAll("[data-schema]"))
    t.addEventListener("click",()=>{state.display=t.dataset.schema;renderContract();});
  $("copyJsonBtn").addEventListener("click",()=>sendCopy(detailJSON(state.endpoint?.[state.display])));
  const curl=()=>sendCopy(state.endpoint?.curl);
  $("copyCurlBtn").addEventListener("click",curl);$("copyCurlInline").addEventListener("click",curl);
  $("viewIntegrationsBtn").addEventListener("click",()=>{visible("integrations");send("integrations");});
  $("scanIntegrationsBtn").addEventListener("click",()=>send("integrations"));
  $("riskAssessBtn").addEventListener("click",()=>startAssessment("isolated"));
  $("evaluateBtn").addEventListener("click",()=>startAssessment($("scope").value));
  $("cancelBtn").addEventListener("click",()=>send("cancel-evaluation"));
  $("copyReportBtn").addEventListener("click",()=>sendCopy(state.report?JSON.stringify(state.report,null,2):""));
  $("contractFileBtn").addEventListener("click",()=>$("contractFile").click());
  $("contractFile").addEventListener("change",async event=>{
    const file=event.target.files?.[0];if(!file)return;
    if(file.size>180000)return toast("Contrato acima do limite de 180 KB.");
    state.bindingName=file.name;
    $("contractText").value=await file.text();event.target.value="";
    toast("Contrato carregado localmente: "+file.name);
  });
  $("saveContractBtn").addEventListener("click",()=>send("bind-contract",{
    key:$("contractTarget").value,mode:$("bindingKind").value,source:state.bindingName,
    raw:$("contractText").value}));
  $("removeContractBtn").addEventListener("click",()=>send("unbind-contract",{
    key:$("contractTarget").value,mode:$("bindingKind").value}));
  $("compareContractBtn").addEventListener("click",()=>send("compare",{
    key:$("contractTarget").value,mode:$("bindingKind").value,
    raw:$("contractText").value,candidateId:-1}));
  for(const tab of document.querySelectorAll("[data-source]"))
    tab.addEventListener("click",()=>{
      state.source=tab.dataset.source;
      document.querySelectorAll("[data-source]").forEach(b=>b.classList.toggle("active",b===tab));
      toggle($("sourceCurl"),state.source==="curl");toggle($("sourceCollection"),state.source==="collection");
      renderSearch();});
  $("importCollectionBtn").addEventListener("click",()=>$("collectionFileInput").click());
  $("collectionFileInput").addEventListener("change",async event=>{
    const file=event.target.files?.[0];if(!file)return;
    if(file.size>5000000)return toast("Collection acima de 5 MB.");
    state.collectionRaw=await file.text();state.collectionName=file.name;
    p("collectionPreview",state.collectionRaw.slice(0,4000));event.target.value="";
  });
  $("searchBtn").addEventListener("click",()=>{
    const raw=state.source==="curl"?$("curlInput").value:state.collectionRaw;
    if(!raw.trim())return toast("Informe uma entrada cURL ou importe uma collection.");
    send("scan",{source:state.source,raw});p("searchSummary","Analisando o projeto…");
  });
  initSplit();renderCatalog();renderContract();renderIntegrations();renderRisks();renderReport();renderHistory();
  send("ready");
})();