const API = `${location.origin}/wolftaxi-api`;
const state = { token:localStorage.getItem('wolftaxi_token')||'', user:null, data:null, timer:null, map:null, markers:new Map() };
const $ = id => document.getElementById(id);
const esc = v => String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = v => `${Number(v||0).toFixed(2)} zł`;

async function request(path, method='GET', body=null){
  const r=await fetch(API+path,{method,headers:{'Accept':'application/json','Content-Type':'application/json','Authorization':state.token?`Bearer ${state.token}`:''},body:body?JSON.stringify(body):undefined});
  const text=await r.text(); let json={}; try{json=text?JSON.parse(text):{}}catch(_){json={message:text}}
  if(r.status===401){logout(); throw new Error('Sesja wygasła.');}
  if(!r.ok) throw new Error(json.message||`HTTP ${r.status}`); return json;
}
function toast(msg,bad=false){const t=$('toast');t.textContent=msg;t.className=`toast${bad?' bad':''}`;setTimeout(()=>t.classList.add('hidden'),3200)}
function roles(){return state.user?.roles||[]}
function hasRole(r){return roles().includes(r)}

async function login(){
  $('loginError').textContent='';
  try{
    const x=await request('/api/v1/auth/login','POST',{email:$('email').value,password:$('password').value});
    if(!(x.user?.roles||[]).some(r=>r==='dispatcher'||r==='admin')) throw new Error('To konto nie ma dostępu do dyspozytorni.');
    state.token=x.token; state.user=x.user; localStorage.setItem('wolftaxi_token',state.token); showApp();
  }catch(e){$('loginError').textContent=e.message}
}
function logout(){localStorage.removeItem('wolftaxi_token');state.token='';state.user=null;clearInterval(state.timer);$('app').classList.add('hidden');$('login').classList.remove('hidden')}
async function bootstrap(){
  if(!state.token)return;
  try{const me=await request('/api/v1/auth/me');if(!me.roles.some(r=>r==='dispatcher'||r==='admin'))throw new Error();state.user=me;showApp()}catch(_){logout()}
}
function showApp(){
  $('login').classList.add('hidden');$('app').classList.remove('hidden');$('who').textContent=`${state.user.name||state.user.email} · ${roles().join('/')}`;$('adminTab').classList.toggle('hidden',!hasRole('admin'));initMap();refresh();clearInterval(state.timer);state.timer=setInterval(refresh,2500)
}
async function refresh(){
  if(!state.token)return;
  try{state.data=await request(hasRole('admin')?'/api/v1/admin/snapshot':'/api/v1/dispatch/snapshot');render()}catch(e){toast(e.message,true)}
}
function render(){const d=state.data;if(!d)return;renderStats();renderDrivers();renderOrders();renderMessages();renderSelects();if(hasRole('admin'))renderUsers();renderMap()}
function renderStats(){const d=state.data;const online=d.drivers.filter(x=>x.online).length,q=d.drivers.filter(x=>x.queuePosition>0).length,active=d.orders.filter(x=>['offered','accepted','en_route','arrived','in_progress'].includes(x.status)).length,waiting=d.orders.filter(x=>['searching_driver','no_driver'].includes(x.status)).length;$('stats').innerHTML=[[online,'Taxi online'],[q,'W kolejkach'],[active,'Aktywne kursy'],[waiting,'Czeka na kierowcę']].map(x=>`<div class="stat"><b>${x[0]}</b><span>${x[1]}</span></div>`).join('')}
function statusBadge(x){let c=x.online?'green':'';if(['offer_received','driving_to_pickup','at_pickup','in_ride'].includes(x.status))c='orange';return `<span class="badge ${c}">${esc(x.status)}</span>`}
function driverRow(x,mini=false){return `<div class="row-item"><div><b>${esc(x.taxiId||`TX${x.number}`)}</b><div class="muted">${esc(x.name)}</div></div><div>${statusBadge(x)}<div class="muted">${x.onShift?'zmiana aktywna':'poza zmianą'}</div></div><div>${esc(x.queueRegionId||x.currentRegionId||'—')} ${x.queuePosition?`· ${x.queuePosition}/${x.queueSize}`:''}<div class="muted">Taryfa ${esc(x.currentTariffId||'—')} · strefa ${esc(x.currentFareZoneId||'—')}</div></div><div>${x.lastLocationAt?new Date(x.lastLocationAt).toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'}):'—'}</div></div>`}
function renderDrivers(){$('driversList').innerHTML=state.data.drivers.map(x=>driverRow(x)).join('')||'<div class="muted">Brak kierowców.</div>';$('driverMini').innerHTML=state.data.drivers.slice(0,12).map(x=>driverRow(x,true)).join('')}
function orderRow(o){const canCancel=!['completed','cancelled'].includes(o.status);const candidates=state.data.drivers.filter(d=>d.onShift&&d.enabled).map(d=>`<option value="${d.id}">${esc(d.taxiId)} · ${esc(d.status)}</option>`).join('');return `<div class="row-item"><div><b>${esc(o.id)}</b><div class="muted">${new Date(o.createdAt).toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'})}</div></div><div><b>${esc(o.pickupAddress)}</b><div class="muted">→ ${esc(o.destinationAddress||'—')}</div></div><div><span class="badge">${esc(o.status)}</span><div class="muted">R: ${esc(o.pickupRegionId||'—')} · ${money(o.estimatedPrice)}</div></div><div class="actions">${canCancel?`<select id="a-${esc(o.id)}"><option value="">Taxi…</option>${candidates}</select><button onclick="assignOrder('${esc(o.id)}')">Przypisz</button><button class="danger" onclick="cancelOrder('${esc(o.id)}')">Anuluj</button>`:''}</div></div>`}
function renderOrders(){const list=state.data.orders.map(orderRow).join('')||'<div class="muted">Brak zleceń.</div>';$('ordersList').innerHTML=list;$('orderMini').innerHTML=state.data.orders.filter(o=>!['completed','cancelled'].includes(o.status)).slice(0,10).map(orderRow).join('')||'<div class="muted">Brak aktywnych zleceń.</div>'}
function renderMessages(){$('messagesList').innerHTML=state.data.messages.map(m=>`<div class="row-item"><div><b>${esc(m.title||m.type)}</b></div><div style="grid-column:span 2">${esc(m.body)}</div><div class="muted">${new Date(m.createdAt).toLocaleString('pl-PL')}</div></div>`).join('')||'<div class="muted">Brak komunikatów.</div>'}
function renderSelects(){const regions='<option value="">—</option>'+state.data.regions.filter(x=>x.active).map(x=>`<option value="${x.id}">${esc(x.shortName||x.id)} · ${esc(x.name)}</option>`).join('');const tariffs='<option value="">—</option>'+state.data.tariffs.filter(x=>x.active).map(x=>`<option value="${x.id}">${esc(x.shortName||x.id)} · ${money(x.pricePerKm)}/km</option>`).join('');$('orderRegion').innerHTML=regions;$('orderTariff').innerHTML=tariffs}
function renderUsers(){const box=$('usersList');if(!box)return;box.innerHTML=(state.data.users||[]).map(u=>`<div class="row-item"><div><b>${esc(u.name||u.email)}</b><div class="muted">${esc(u.email)}</div></div><div>${u.roles.map(r=>`<span class="badge">${esc(r)}</span>`).join(' ')}</div><div>${u.enabled?'<span class="badge green">aktywne</span>':'<span class="badge red">zablokowane</span>'}</div><div class="actions"><button onclick="toggleUser('${u.id}',${!u.enabled})">${u.enabled?'Zablokuj':'Odblokuj'}</button></div></div>`).join('')}
function initMap(){if(state.map||!window.L)return;state.map=L.map('map').setView([52.1,19.4],6);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(state.map)}
function renderMap(){if(!state.map)return;const valid=state.data.drivers.filter(d=>Number.isFinite(d.lat)&&Number.isFinite(d.lng));const ids=new Set(valid.map(d=>d.id));for(const [id,m] of state.markers)if(!ids.has(id)){m.remove();state.markers.delete(id)}for(const d of valid){let m=state.markers.get(d.id);if(!m){m=L.marker([d.lat,d.lng]).addTo(state.map);state.markers.set(d.id,m)}else m.setLatLng([d.lat,d.lng]);m.bindPopup(`<b>${esc(d.taxiId)}</b><br>${esc(d.status)}<br>${esc(d.currentRegionId||'')}`)}if(valid.length&&state.markers.size===valid.length){const b=L.latLngBounds(valid.map(d=>[d.lat,d.lng]));if(b.isValid()&&valid.length>1)state.map.fitBounds(b.pad(.25));else if(valid.length===1)state.map.setView([valid[0].lat,valid[0].lng],14)}}

async function createOrder(){try{const x=await request('/api/v1/dispatch/orders','POST',{pickupAddress:$('pickup').value,destinationAddress:$('destination').value,pickupRegionId:$('orderRegion').value,tariffId:$('orderTariff').value,passengerName:$('passengerName').value,passengerPhone:$('passengerPhone').value,notes:$('orderNotes').value,estimatedPrice:Number($('estimatedPrice').value||0),cardRequired:$('cardRequired').checked});toast(x.message);$('pickup').value='';$('destination').value='';refresh()}catch(e){toast(e.message,true)}}
window.assignOrder=async id=>{const driverId=$(`a-${id}`)?.value;if(!driverId)return toast('Wybierz taxi.',true);try{toast((await request(`/api/v1/dispatch/orders/${encodeURIComponent(id)}/assign`,'POST',{driverId})).message);refresh()}catch(e){toast(e.message,true)}}
window.cancelOrder=async id=>{if(!confirm(`Anulować ${id}?`))return;try{toast((await request(`/api/v1/dispatch/orders/${encodeURIComponent(id)}/cancel`,'POST',{})).message);refresh()}catch(e){toast(e.message,true)}}
async function sendMessage(){try{toast((await request('/api/v1/dispatch/messages','POST',{title:$('messageTitle').value,type:$('messageType').value,body:$('messageBody').value})).message);$('messageBody').value='';refresh()}catch(e){toast(e.message,true)}}
async function createUser(){const rs=[];if($('roleDriver').checked)rs.push('driver');if($('roleDispatcher').checked)rs.push('dispatcher');if($('roleAdmin').checked)rs.push('admin');try{toast((await request('/api/v1/admin/users','POST',{email:$('newUserEmail').value,name:$('newUserName').value,password:$('newUserPassword').value,roles:rs,taxiId:$('newTaxiId').value,number:Number($('newTaxiNumber').value||0)})).message);$('newUserPassword').value='';refresh()}catch(e){toast(e.message,true)}}
window.toggleUser=async(id,enabled)=>{try{toast((await request(`/api/v1/admin/users/${id}/enabled`,'POST',{enabled})).message);refresh()}catch(e){toast(e.message,true)}}
async function saveTariff(){const id=$('tariffId').value.trim();if(!id)return toast('Podaj ID taryfy.',true);try{toast((await request(`/api/v1/admin/tariffs/${encodeURIComponent(id)}`,'POST',{name:$('tariffName').value||id,shortName:id,startFee:Number($('tariffStart').value||0),pricePerKm:Number($('tariffKm').value||0),active:true})).message);refresh()}catch(e){toast(e.message,true)}}
async function saveRegion(){const id=$('regionId').value.trim();if(!id)return toast('Podaj ID regionu.',true);try{toast((await request(`/api/v1/admin/regions/${encodeURIComponent(id)}`,'POST',{name:$('regionName').value||id,shortName:id,priority:Number($('regionPriority').value||0),queueEnabled:true,active:true})).message);refresh()}catch(e){toast(e.message,true)}}
async function saveZone(){const id=$('zoneId').value.trim();if(!id)return toast('Podaj ID strefy.',true);try{toast((await request(`/api/v1/admin/fare-zones/${encodeURIComponent(id)}`,'POST',{name:$('zoneName').value||id,defaultTariffId:$('zoneTariff').value||null,multiplier:1,active:true})).message);refresh()}catch(e){toast(e.message,true)}}

$('loginBtn').onclick=login;$('password').addEventListener('keydown',e=>{if(e.key==='Enter')login()});$('logoutBtn').onclick=logout;$('refreshBtn').onclick=refresh;$('createOrderBtn').onclick=createOrder;$('sendMessageBtn').onclick=sendMessage;$('createUserBtn').onclick=createUser;$('saveTariffBtn').onclick=saveTariff;$('saveRegionBtn').onclick=saveRegion;$('saveZoneBtn').onclick=saveZone;
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.tabs button').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));b.classList.add('active');$(b.dataset.tab).classList.add('active');setTimeout(()=>state.map?.invalidateSize(),20)});
bootstrap();
