/* Native JCEF adapter for the approved JSON Lens HTML.
 * Browser = presentation only. All PSI, matching and contract calculations are Java.
 * No network transport, HTTP execution or external scripts.
 */
(function () {
  'use strict';
  const el = id => document.getElementById(id);
  const bridge = (type, fields = {}) => {
    if (typeof window.__jsonLensNative === 'function') window.__jsonLensNative({type, ...fields});
  };
  const escape = value => String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const model = {
    endpoint: null, methods: [], selected: '', pinned: false, payload: 'request', compareCandidateId: -1,
    compareTarget: null,
    source: 'curl', collectionRaw: '', searches: {curl: null, collection: null},
    actualJson: '', contractMode: 'request', compareTimer: null
  };
  let renderGeneration = 0;
  let renderedSource = null;
  let renderedSearch = null;

  const style = document.createElement('style');
  style.textContent = `
    html,body{height:100%;width:100%;margin:0;overflow:hidden;background:#0c0d10}
    .plugin-shell{height:100vh!important;min-height:0!important;width:100%!important;
      padding:0!important;margin:0!important;border:0!important;max-width:none!important}
    .plugin-shell>.lens{width:100%!important;height:100%!important;min-width:0!important;
      max-width:none!important;margin:0!important;border:0!important;border-radius:0!important;
      box-shadow:none!important}
    .lens-scroll,.lens .view,.lens .section,.endpoint-card,.codebox,.match-card,
    .lab-search-split,.lab-search-entry,.lab-search-results{min-width:0;max-width:100%}
    .endpoint-route,.endpoint-meta,.match-route,.match-name,.detail-row,
    .finding,.lab-mini-card,.lead,.hint{overflow-wrap:anywhere;word-break:break-word}
    pre,.code-json,.lab-json-preview{overflow:auto;max-width:100%;white-space:pre}
    .nav button[onclick*="coverage"],#view-coverage,.fixture-accordion,
    .lab-stamp,#fixture-bridge,.lab-mini-note{display:none!important}
    .lab-search-split{min-height:0}
    .lens button{white-space:normal}
    .lens .match-footer{display:flex;flex-wrap:wrap}
    .lens .source-tabs{flex-wrap:wrap}
    @media(max-width:440px){
       .lens .compare-grid{grid-template-columns:1fr}
       .lens .endpoint-controls,.lens .lab-flex{flex-wrap:wrap}
       .lens .endpoint-controls .select{flex:1 1 170px}
       .lens .match-footer button{flex:1 1 100px}
    }
  `;
  document.head.appendChild(style);

  const sourceNoDemo = () => {
    if (el('previewStatus')) el('previewStatus').textContent = '● Análise PSI local';
    if (el('liveCompareStatus')) el('liveCompareStatus').textContent = '● Comparação real no Java';
    if (el('sourceCacheStatus')) el('sourceCacheStatus').textContent =
      'cURL e collection: resultados independentes, analisados no projeto Java.';
    const title = document.querySelector('#view-preview .line-top [style*="Acompanhando"]');
    if (title) title.textContent = 'Acompanhando o editor';
    const lead = document.querySelector('#view-compare .compare-hero .lead');
    if (lead) lead.textContent =
      'Compare o request ou response Java com JSON de exemplo, JSON Schema ou OpenAPI. Análise estática local.';
    const note = document.querySelector('#view-compare .hint');
    if (note) note.textContent = 'Comparação feita pelo parser Java do plugin. Não executa a aplicação.';
    const stamp = document.querySelector('#view-preview .section-kicker');
    if (stamp) stamp.textContent = '01 / ENDPOINT ATUAL';
    const demoLine = document.querySelector('#view-search .search-hero .lead');
    if (demoLine) demoLine.textContent = 'Localize controllers e clientes Feign reais no projeto. Nenhuma requisição é executada.';
    const detailsCatalog = el('modelPreset');
    if (detailsCatalog) detailsCatalog.closest('.lab-extra-section')?.setAttribute('hidden', 'hidden');
    const mappingCatalog = el('mappingPreset');
    if (mappingCatalog) mappingCatalog.closest('.lab-extra-section')?.setAttribute('hidden', 'hidden');
  };

  function renderPreview() {
    const endpoint = model.endpoint;
    el('reqTab')?.classList.toggle('active', model.payload === 'request');
    el('resTab')?.classList.toggle('active', model.payload === 'response');
    if (!endpoint) {
      if (el('jsonPreview')) el('jsonPreview').textContent =
        '// Selecione um endpoint Java na IDE para visualizar seu JSON.';
      if (el('endpointRoute')) el('endpointRoute').textContent = 'Nenhum endpoint selecionado';
      if (el('endpointMeta')) el('endpointMeta').textContent = 'Posicione o cursor em um método mapeado.';
      if (el('previewWarning')) el('previewWarning').hidden = true;
      return;
    }
    const schema = !!el('schemaToggle')?.checked;
    const key = model.payload + (schema ? 'Schema' : '');
    const json = endpoint[key] == null ? null : endpoint[key];
    model.actualJson = JSON.stringify(json, null, 2);
    el('jsonPreview').innerHTML = jsonHighlight(model.actualJson);
    el('httpBadge').textContent = endpoint.verb || '—';
    el('directionBadge').textContent = endpoint.direction || 'JAVA';
    el('endpointRoute').textContent = endpoint.path || '';
    el('endpointMeta').textContent = endpoint.title || '';
    el('codeboxTitle').textContent = model.payload + (schema ? '.schema.json' : '.json');
    el('previewFileInfo').textContent = schema ? 'Schema inferido do código' : 'Valores fictícios · não executados';
    el('previewStatus').textContent = '● JSON atualizado pelo Java PSI';
    const warnings = [...(endpoint.warnings || []), ...(endpoint.notes || [])];
    el('previewWarning').hidden = warnings.length === 0;
    el('warningMessage').textContent = warnings.slice(0, 8).join(' • ');
    if (el('detailSignature')) el('detailSignature').textContent = endpoint.title || '';
    if (el('detailDirection')) el('detailDirection').textContent = endpoint.direction || '';
    if (el('detailRoute')) el('detailRoute').textContent = (endpoint.verb || '') + ' ' + (endpoint.path || '');
    const notesHost = el('nativeDetailsNotes');
    if (notesHost) {
      notesHost.replaceChildren();
      const entries = [...(endpoint.notes || []), ...(endpoint.warnings || [])];
      notesHost.textContent = entries.length ? entries.join('\\n') : 'Nenhum aviso adicional para este endpoint.';
    }
    if (el('actualFields')) el('actualFields').textContent =
      Object.keys((endpoint.requestSchema || {}).properties || endpoint.request || {}).join(' · ') || '—';
    if (el('compareActualLabel')) el('compareActualLabel').textContent = endpoint.title || '';
  }

  function renderMethods() {
    const select = el('methodSelect');
    if (!select) return;
    select.replaceChildren(...model.methods.map(method => {
      const option = document.createElement('option');
      option.value = method.key;
      option.textContent = method.label;
      return option;
    }));
    select.value = model.selected;
    select.disabled = !model.methods.length;
    if (el('pinBtn')) el('pinBtn').textContent = model.pinned ? '♧  Desafixar' : '♧  Fixar';
    if (el('syncStatus')) el('syncStatus').textContent = model.endpoint
      ? 'Live sync · Java PSI' : 'Abra um endpoint Java';
  }

  // The prototype's public handlers are intentionally replaced, not layered on top.
  chooseEndpoint = key => { model.compareCandidateId = -1; model.compareTarget = null; bridge('select', {key}); };
  choosePayload = kind => { model.payload = kind; renderPreview(); };
  togglePin = () => bridge('pin');
  copyPayload = () => bridge('copy', {text: model.actualJson});
  window.renderPreview = renderPreview;
  toggleDto = () => toast('Edite o DTO diretamente no editor Java do IntelliJ.');
  toggleJavaEditor = () => toast('Use o editor Java real; esta janela apenas inspeciona o projeto.');

  renderSearchCache = () => {};
  const oldNavigate = navigate;
  navigate = function (view) {
    if (view === 'coverage') return;
    if (view === 'compare' && document.querySelector('#view-preview:not([hidden])')) {
      model.compareCandidateId = -1;
      model.compareTarget = null;
    }
    oldNavigate(view);
    if (view === 'search') renderResults();
    if (view === 'compare') scheduleNativeCompare();
  };

  function sourceChanged(source) {
    model.source = source;
    el('sourceCurlTab').classList.toggle('active', source === 'curl');
    el('sourceCollectionTab').classList.toggle('active', source === 'collection');
    el('sourceCurl').hidden = source !== 'curl';
    el('sourceCollection').hidden = source !== 'collection';
    renderResults();
    if (!model.searches[source]) bridge('source', {source});
  }
  chooseSource = sourceChanged;

  runSearch = function () {
    const source = model.source;
    const raw = source === 'curl' ? el('curlText').value : model.collectionRaw;
    if (!raw?.trim()) {
      toast(source === 'curl' ? 'Cole um cURL para localizar.' : 'Carregue uma collection JSON.');
      return;
    }
    el('scanSummary').textContent = 'Analisando endpoints Java indexados…';
    el('resultCount').textContent = 'Verificando';
    bridge('scan', {source, raw});
  };

  loadCurlFile = async function (input) {
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 2_000_000) return toast('Arquivo cURL maior que 2 MB.');
    el('curlText').value = await file.text();
    input.value = '';
    sourceChanged('curl');
  };

  loadCollectionFile = async function (input) {
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 5_000_000) return toast('Collection maior que 5 MB.');
    model.collectionRaw = await file.text();
    input.value = '';
    renderCollectionFile(file.name);
    sourceChanged('collection');
  };

  const exampleCollection = loadSampleCollection;
  loadSampleCollection = function (kind) {
    exampleCollection(kind);
    model.collectionRaw = JSON.stringify(LAB.rawCollection || {});
    renderCollectionFile(kind === 'postman' ? 'exemplo.postman.json' : 'exemplo.insomnia.json');
    sourceChanged('collection');
  };

  function renderCollectionFile(name) {
    el('collectionName').textContent = name;
    if (model.collectionRaw.length > 250000) {
      el('collectionPreview').innerHTML = jsonHighlight(model.collectionRaw.slice(0, 20000));
      el('collectionCount').textContent = '—';
      el('collectionInfo').textContent =
        'Prévia limitada para manter a interface responsiva; o arquivo completo será analisado.';
      return;
    }
    try {
      const doc = JSON.parse(model.collectionRaw);
      const json = JSON.stringify(doc, null, 2);
      el('collectionPreview').innerHTML = jsonHighlight(json);
      const len = Array.isArray(doc.item) ? doc.item.length
        : Array.isArray(doc.resources) ? doc.resources.filter(x => x._type === 'request').length : 0;
      el('collectionCount').textContent = len + ' requests';
      el('collectionInfo').textContent = 'Arquivo local · será analisado pelo parser Java';
    } catch (error) {
      el('collectionPreview').textContent = 'JSON inválido: ' + error.message;
    }
  }

  function matchCard(match) {
    const card = document.createElement('div');
    card.className = 'match-card';
    const percentage = match.adherence < 0 ? '—' : match.adherence + '%';
    const evidences = (match.evidence || []).map(line =>
      '<div><span>✓</span> ' + escape(line) + '</div>').join('');
    card.innerHTML =
      '<div class="lab-coverage-top"><div><div class="match-name">' + escape(match.title) +
      '</div><div class="match-route">' + escape(match.method + ' ' + match.path) + '</div></div>' +
      '<div style="text-align:right"><div class="match-score">' + Number(match.score) +
      '/100</div><div class="score-caption">MATCH · ranking</div>' +
      '<div class="lab-status" style="margin-top:6px">ADERÊNCIA ' + escape(percentage) +
      '</div></div></div><div class="scorebar"><span style="width:' +
      Math.max(0, Math.min(100, Number(match.score) || 0)) + '%"></span></div>' +
      '<div class="match-evidence">' + evidences + '<small>' +
      escape(match.direction || '') + ' · ' + escape(match.file || '') + '</small></div>' +
      '<div class="match-details" hidden></div>' +
      '<div class="match-footer"><button class="card-btn" data-action="expand">Expandir</button>' +
      '<button class="card-btn highlight" data-action="contract">Comparar contrato</button>' +
      '<button class="card-btn" data-action="open">Abrir código ↗</button></div>';
    card.querySelector('[data-action="expand"]').addEventListener('click', event => {
      const details = card.querySelector('.match-details');
      details.hidden = !details.hidden;
      if (!details.hidden && !details.hasChildNodes()) {
        const findings = (match.findings || []).filter(x => x.severity !== 'INFO').map(
          x => '<div class="finding"><span class="level">' + escape(x.severity) +
            '</span><span>' + escape(x.pointer) + ' — ' + escape(x.message) + '</span></div>').join('');
        details.innerHTML = '<div class="lab-grid">' +
          '<div class="lab-mini-card"><strong>Request inferido</strong><pre class="lab-json-preview">' +
          escape(JSON.stringify(match.request, null, 2)) + '</pre></div>' +
          '<div class="lab-mini-card"><strong>Response inferido</strong><pre class="lab-json-preview">' +
          escape(JSON.stringify(match.response, null, 2)) + '</pre></div></div>' +
          (findings || '<p>Nenhuma divergência estrutural apontada por esta comparação estática.</p>');
      }
      event.currentTarget.textContent = details.hidden ? 'Expandir' : 'Recolher';
    });
    card.querySelector('[data-action="contract"]').addEventListener('click', () => {
      model.compareCandidateId = Number(match.id);
      model.compareTarget = {source: model.source, key: match.key};
      el('compareActualLabel').textContent = match.title || '';
      el('actualFields').textContent = Object.keys(match.request || {}).join(' · ') || '—';
      navigate('compare');
      scheduleNativeCompare();
    });
    card.querySelector('[data-action="open"]').addEventListener('click', () => bridge('open', {id: match.id}));
    return card;
  }

  function renderResults() {
    const holder = el('resultsHolder'), cached = model.searches[model.source];
    if (renderedSource === model.source && renderedSearch === cached) return;
    renderedSource = model.source;
    renderedSearch = cached;
    const generation = ++renderGeneration;
    holder.replaceChildren();
    if (!cached) {
      el('resultCount').textContent = 'Sem busca';
      el('scanSummary').textContent = 'Execute uma busca para localizar endpoints reais no projeto Java.';
      return;
    }
    const requests = cached.requests || [];
    el('resultCount').textContent = requests.length + ' request(s)';
    el('scanSummary').textContent = cached.scanned + ' endpoint(s) indexados. Match e aderência são medidas distintas.';
    el('sourceCacheStatus').textContent = 'Busca ' + model.source + ' preservada nesta sessão.';
    let index = 0;
    function renderBatch() {
      if (generation !== renderGeneration) return;
      const fragment = document.createDocumentFragment();
      const end = Math.min(index + 5, requests.length);
      for (; index < end; index++) {
        const request = requests[index];
        const header = document.createElement('div');
        header.className = 'match-category';
        header.textContent = (request.method || '') + ' ' + (request.path || '') + ' · ' + (request.name || '');
        fragment.appendChild(header);
        for (const [title, matches] of [
          ['↘ RECEBEM / CONTROLLERS', request.inbound || []],
          ['↗ ENVIAM / FEIGN CLIENTS', request.outbound || []]
        ]) {
          const category = document.createElement('div');
          category.className = 'match-category';
          category.textContent = title;
          fragment.appendChild(category);
          if (matches.length) matches.forEach(match => fragment.appendChild(matchCard(match)));
          else {
            const empty = document.createElement('p');
            empty.className = 'hint';
            empty.textContent = 'Nenhum endpoint deste tipo encontrado.';
            fragment.appendChild(empty);
          }
        }
      }
      holder.appendChild(fragment);
      if (index < requests.length) requestAnimationFrame(renderBatch);
    }
    renderBatch();
  }

  selectCompareMode = function (mode) {
    model.contractMode = mode;
    LAB.compareMode = mode;
    el('compareRequestTab').classList.toggle('active', mode === 'request');
    el('compareResponseTab').classList.toggle('active', mode === 'response');
    scheduleNativeCompare();
  };
  compareContracts = function () {
    bridge('compare', {raw: el('contractInput').value, mode: model.contractMode, candidateId: model.compareCandidateId});
  };
  function scheduleNativeCompare() {
    clearTimeout(model.compareTimer);
    el('liveCompareStatus').textContent = '◌ Comparando contrato no Java…';
    model.compareTimer = setTimeout(() => compareContracts(), 350);
  }
  scheduleLiveCompare = scheduleNativeCompare;
  function renderComparison(data) {
    const box = el('contractFindings');
    box.replaceChildren();
    if (data.error) {
      el('contractScore').textContent = '—';
      el('scoreDescription').textContent = 'Contrato inválido ou incompleto';
      const warning = document.createElement('div');
      warning.className = 'finding';
      warning.textContent = data.error;
      box.appendChild(warning);
      return;
    }
    el('contractScore').textContent = data.score + '%';
    el('contractScore').style.color = data.score === 100 ? 'var(--green)' : 'var(--orange2)';
    const errors = (data.findings || []).filter(f => f.severity === 'ERROR').length;
    const warns = (data.findings || []).filter(f => f.severity === 'WARNING').length;
    el('scoreDescription').textContent = errors + ' erro(s) · ' + warns + ' aviso(s) · estático';
    el('contractSourceType').textContent = data.sourceType || 'Contrato';
    for (const issue of [...(data.findings || []), ...(data.warnings || []).map(s => ({
      severity:'INFO', pointer:'$', message:s
    }))]) {
      const row = document.createElement('div');
      row.className = 'finding' + (issue.severity === 'INFO' ? ' ok' : '');
      row.textContent = (issue.severity || 'INFO') + ' ' + (issue.pointer || '$') +
        ' — ' + (issue.message || '');
      box.appendChild(row);
    }
    if (!box.children.length) {
      const ok = document.createElement('div');
      ok.className = 'finding ok';
      ok.textContent = 'Nenhuma incompatibilidade estrutural identificada; execução não validada.';
      box.appendChild(ok);
    }
    el('liveCompareStatus').textContent = '● Analisado pelo parser Java · sem HTTP';
  }

  const oldSavePreferences = savePreferences;
  savePreferences = function () {
    oldSavePreferences();
    const value = id => el(id)?.value || '';
    const theme = {ember:'Ember', midnight:'Midnight', base16:'Base16', ocean:'Ocean'};
    const font = choice => choice === 'mono' ? 'Monospaced' : choice === 'serif' ? 'Serif' : 'SansSerif';
    bridge('preferences', {
      language: value('languagePref') === 'en' ? 'en' : 'pt-BR',
      theme: theme[value('themePref')] || 'Ember',
      infoFont: font(value('infoFontPref')), infoSize: Number(value('infoSizePref')) || 12,
      jsonFont: font(value('jsonFontPref')), jsonSize: Number(value('jsonSizePref')) || 15,
      curlFont: font(value('curlFontPref')), curlSize: Number(value('curlSizePref')) || 13
    });
  };

  window.JsonLensUI = {
    onNative(data) {
      switch (data.type) {
        case 'ready':
          if (data.language) el('languagePref').value = data.language === 'en' ? 'en' : 'pt';
          if (data.theme) {
            const val = {Ember:'ember', Midnight:'midnight', Base16:'base16', Ocean:'ocean'}[data.theme];
            if (val) el('themePref').value = val;
          }
          for (const field of ['info', 'json', 'curl']) {
            const font = data[field + 'Font'];
            const size = data[field + 'Size'];
            const choice = el(field + 'FontPref'), scale = el(field + 'SizePref');
            if (choice && font) {
              const family = String(font).toLowerCase();
              choice.value = /mono|consolas|cascadia/.test(family) ? 'mono'
                : /serif/.test(family) ? 'serif' : 'ui';
              if (![...choice.options].some(o => o.value === choice.value)) choice.value = 'ui';
            }
            if (scale && size) scale.value = String(size);
          }
          oldSavePreferences();
          break;
        case 'preview':
          model.endpoint = data.endpoint || null;
          model.methods = data.methods || [];
          model.selected = data.selectedKey || '';
          model.pinned = !!data.pinned;
          renderMethods();
          renderPreview();
          if (data.note && !data.endpoint) el('previewStatus').textContent = data.note;
          break;
        case 'pin':
          model.pinned = !!data.pinned;
          renderMethods();
          break;
        case 'search':
          model.searches[data.source] = data;
          if (model.compareTarget?.source === data.source) {
            const matches = (data.requests || []).flatMap(request =>
              [...(request.inbound || []), ...(request.outbound || [])]);
            const updated = matches.find(match => match.key === model.compareTarget.key);
            model.compareCandidateId = updated ? Number(updated.id) : -2;
            if (updated && el('contractInput')?.value.trim()) scheduleNativeCompare();
            if (!updated) toast('O endpoint não está mais nos resultados; selecione-o novamente.');
          }
          if (model.source === data.source) renderResults();
          break;
        case 'scan-error':
          if (model.source === data.source) {
            el('scanSummary').textContent = data.message || 'Não foi possível analisar.';
            el('resultCount').textContent = 'Erro';
          }
          toast(data.message || 'Erro ao analisar entrada.');
          break;
        case 'comparison': renderComparison(data); break;
        case 'recompare': if (el('contractInput')?.value.trim()) scheduleNativeCompare(); break;
        case 'notice': toast(data.message); break;
      }
    }
  };

  // Prevent prototype-only source edit and external navigation inside the embedded Chromium.
  document.addEventListener('click', event => {
    const button = event.target.closest('button[onclick*="toggleDto"],button[onclick*="toggleJavaEditor"]');
    if (button) {
      event.preventDefault(); event.stopImmediatePropagation();
      toast('Para alterar o código, utilize o editor Java do IntelliJ.');
    }
    const github = event.target.closest('button[onclick*="window.open"]');
    if (github) {
      event.preventDefault(); event.stopImmediatePropagation();
      toast('Código-fonte disponível no GitHub: codewesleylima/Json-Lens');
    }
  }, true);

  // The contract chooser is an actual local file input, not a prototype mock.
  const contractBox = el('contractInput');
  if (contractBox) {
    const actions = document.createElement('div');
    actions.className = 'row-actions';
    const chooser = document.createElement('input');
    chooser.type = 'file';
    chooser.accept = '.json,.yaml,.yml,application/json,text/yaml,text/plain';
    chooser.style.display = 'none';
    const button = document.createElement('button');
    button.className = 'secondary';
    button.textContent = 'Carregar contrato JSON / YAML…';
    button.addEventListener('click', () => chooser.click());
    chooser.addEventListener('change', async () => {
      const file = chooser.files?.[0];
      if (!file) return;
      if (file.size > 1_000_000) return toast('Contrato maior que 1 MB.');
      contractBox.value = await file.text();
      scheduleNativeCompare();
      chooser.value = '';
    });
    actions.append(button, chooser);
    contractBox.parentElement.insertBefore(actions, contractBox);
  }
  const detailsHost = document.querySelector('#view-details .details-list');
  if (detailsHost) {
    const detail = document.createElement('div');
    detail.className = 'detail-row';
    detail.innerHTML = '<label>Parâmetros e avisos encontrados</label><div id="nativeDetailsNotes" style="white-space:pre-wrap;overflow-wrap:anywhere">Abra um endpoint Java.</div>';
    detailsHost.appendChild(detail);
  }
  sourceNoDemo();
  model.endpoint = null;
  model.actualJson = '';
  renderPreview();
  el('contractFindings').replaceChildren();
  el('contractScore').textContent = '—';
  el('scoreDescription').textContent = 'Selecione um endpoint Java para comparar';
  renderResults();
  bridge('ready');
})();
