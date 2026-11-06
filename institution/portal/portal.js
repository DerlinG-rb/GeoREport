'use strict';
let token = '', generation = 0;
const $ = id => document.getElementById(id);
async function api(path) {
  const response = await fetch(path, {headers: {Authorization: `Bearer ${token}`}, cache: 'no-store'});
  const data = await response.json();
  if (!response.ok) throw Error(data.error || 'No se pudo conectar');
  return data;
}
function text(parent, tag, value) { const node = document.createElement(tag); node.textContent = value; parent.append(node); return node; }
async function refresh() {
  const current = generation;
  $('status').textContent = 'Cargando reportes…';
  try {
    const rows = await api('/reports');
    if (current !== generation) return;
    $('reports').replaceChildren();
    for (const row of rows) {
      const button = text($('reports'), 'button', `${row.category} · ${new Date(row.received_at).toLocaleString('es')} · ${row.receipt_id}`);
      button.onclick = () => detail(row.receipt_id);
    }
    $('status').textContent = rows.length ? `${rows.length} reportes recibidos` : 'Todavía no hay reportes recibidos.';
  } catch (e) { if(current === generation) $('status').textContent = `${e.message}. Pulse Actualizar para reintentar.`; }
}
async function detail(id) {
  const current = generation;
  $('status').textContent = 'Cargando detalle…';
  try {
    const row = await api(`/reports/${encodeURIComponent(id)}`);
    if (current !== generation) return;
    const panel = $('detail'); panel.replaceChildren();
    text(panel, 'h2', row.category);
    const image = document.createElement('img'); image.src = row.photo; image.alt = 'Evidencia del incidente recibido'; panel.append(image);
    text(panel, 'p', row.description);
    text(panel, 'p', `Ubicación del incidente: ${row.location.latitude}, ${row.location.longitude}`);
    text(panel, 'p', `Fecha del reporte: ${new Date(row.created_at).toLocaleString('es')}`);
    text(panel, 'p', `Comprobante: ${row.receipt_id} · Recibido: ${new Date(row.received_at).toLocaleString('es')}`);
    $('status').textContent = 'Detalle actualizado';
  } catch(e) { if(current === generation) $('status').textContent = `${e.message}. Seleccione nuevamente el reporte para reintentar.`; }
}
$('access').onsubmit = async event => {
  event.preventDefault(); token = $('token').value; $('token').value = '';
  try {
    const institutions = await api('/institutions');
    if (institutions.length !== 1) throw Error('Use la clave de una institución, no la de integración');
    $('institution').replaceChildren();
    for(const i of institutions) { const option = text($('institution'), 'option', i.name); option.value = i.id; }
    $('access').hidden = true; $('workspace').hidden = false; await refresh();
  } catch(e) { token = ''; $('status').textContent = e.message; }
};
$('refresh').onclick = refresh;
$('institution').onchange = refresh;
$('logout').onclick = () => { generation++; token = ''; $('reports').replaceChildren(); $('detail').replaceChildren(); $('workspace').hidden = true; $('access').hidden = false; $('status').textContent = 'Sesión cerrada'; };
