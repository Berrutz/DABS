(function(){
  const dot = document.getElementById('dot');
  const label = document.getElementById('label');
  const mainHostEl = document.getElementById('mainHost');
  const rmiPortEl = document.getElementById('rmiPort');
  const httpPortEl = document.getElementById('httpPort');
  const hostNameEl = document.getElementById('hostName');
  const updatedEl = document.getElementById('updated');
  const agentList = document.getElementById('agentList');
  const emptyEl = document.getElementById('empty');

  function setStatus(up, ts){
    dot.classList.remove('up','down');
    dot.classList.add(up ? 'up' : 'down');
    label.textContent = up ? 'online' : 'offline';
    if(ts) updatedEl.textContent = new Date(ts).toLocaleString();
  }

  function addAgent(agent){
    const { name, class: clazz, since } = agent;
    let li = agentList.querySelector(`li[data-name="${name}"]`);
    if(!li){
      li = document.createElement('li');
      li.className = 'li-anim';
      li.dataset.name = name;
      const pill = document.createElement('span');
      pill.className = 'pill';
      pill.textContent = name;
      const cls = document.createElement('span');
      cls.className = 'cls';
      cls.textContent = clazz || '?';
      const sinceEl = document.createElement('span');
      sinceEl.className = 'since';
      sinceEl.textContent = since ? new Date(since).toLocaleTimeString() : '-';
      li.append(pill, cls, sinceEl);
      agentList.appendChild(li);
    }else{
      const spans = li.querySelectorAll('span');
      spans[1].textContent = clazz || '?';
      spans[2].textContent = since ? new Date(since).toLocaleTimeString() : '-';
    }
  }

  function removeAgent(name){
    const li = agentList.querySelector(`li[data-name="${name}"]`);
    if(li) agentList.removeChild(li);
  }

  function updateEmpty(){
    emptyEl.style.display = agentList.children.length ? 'none' : '';
  }

  async function init(){
    try{
      const res = await fetch('/api/status');
      const data = await res.json();
      mainHostEl.textContent = data.mainHost;
      rmiPortEl.textContent = data.port;
      httpPortEl.textContent = data.httpMTP;
      hostNameEl.textContent = data.hostname;
      if(data.lastUpdate != null){
        setStatus(data.up, data.lastUpdate);
      }
      agentList.innerHTML = '';
      (data.agents || []).forEach(addAgent);
      updateEmpty();
    }catch(err){
      console.error('init error', err);
    }
  }

  function handleDf(payload){
    if(payload.type === 'REGISTER'){
      addAgent({ name: payload.name, class: payload.clazz, since: payload.when });
    }else if(payload.type === 'DEREGISTER' || payload.type === 'DE-REGISTER'){
      removeAgent(payload.name);
    }
    updateEmpty();
  }

  function connect(){
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}`);
    ws.addEventListener('open', () => {
      init();
    });
    ws.addEventListener('message', ev => {
      try{
        const msg = JSON.parse(ev.data);
        if(msg.kind === 'heartbeat'){
          setStatus(msg.payload.up, msg.payload.ts);
        }else if(msg.kind === 'df'){
          handleDf(msg.payload);
        }
      }catch(err){
        console.error('ws message error', err);
      }
    });
    ws.addEventListener('close', () => {
      setTimeout(connect, 1000);
    });
    ws.addEventListener('error', () => ws.close());
  }

  connect();
})();
