// Genera la colección Postman de la API Spring Boot a partir de la de API-PRO (solo lectura):
// quita SIRE, cotizaciones y notas de venta, conserva solo las peticiones cuya ruta y método
// existen en el OpenAPI de esta API, limpia datos reales y agrega Liquidación de compra.
// Uso (con la API levantada):
//   curl -s http://localhost:8081/api/docs/openapi.json > /tmp/openapi.json
//   node tools/build-postman.js "<API-PRO>/API FACTURACION SUNAT PERU ….postman_collection.json" /tmp/openapi.json \
//     docs/postman/API-SUNAT-Spring-Boot.postman_collection.json
const fs = require('fs');
const [src, spec, out] = process.argv.slice(2);
const col = JSON.parse(fs.readFileSync(src, 'utf8'));
const api = JSON.parse(fs.readFileSync(spec, 'utf8'));

const routes = [];
for (const [p, ops] of Object.entries(api.paths)) {
  const re = new RegExp('^' + p.replace(/\{[^}]+\}/g, '[^/]+') + '$');
  for (const m of Object.keys(ops)) routes.push({ re, method: m.toUpperCase(), path: p });
}
// Las rutas literales (reporte-retenciones) antes que las de parámetro ({id})
routes.sort((a, b) => (a.path.match(/\{/g) || []).length - (b.path.match(/\{/g) || []).length);
const used = new Set();

function pathOf(req) {
  const raw = typeof req.url === 'string' ? req.url : req.url.raw;
  let p = raw.replace(/^\{\{base_url\}\}/, '/api/v1').replace(/^https?:\/\/[^/]+/, '').split('?')[0].split('#')[0];
  p = p.replace(/\{\{[^}]+\}\}/g, 'x').replace(/\/:[A-Za-z_]+/g, '/x');
  return p.replace(/\/+$/, '') || '/';
}

const dropped = [];
function filter(items, trail) {
  const res = [];
  for (const it of items) {
    if (it.item) {
      if (/^(17|21|22)\./.test(it.name)) { dropped.push(`[carpeta] ${it.name}`); continue; }
      it.item = filter(it.item, trail + it.name + ' / ');
      if (it.item.length) res.push(it);
      continue;
    }
    const method = it.request.method;
    const p = pathOf(it.request);
    const r = routes.find((x) => x.method === method && x.re.test(p));
    if (!r) { dropped.push(`${method} ${p}  (${trail}${it.name})`); continue; }
    used.add(r.method + ' ' + r.path);
    delete it.response; // ejemplos de respuesta de API-PRO: se regeneran contra esta API
    res.push(it);
  }
  return res;
}
col.item = filter(col.item, '');

// Cabeceras de autenticación tal como las usa la colección original
let authHeaders = [
  { key: 'X-Api-Key', value: '{{api_key}}' },
  { key: 'X-Api-Secret', value: '{{api_secret}}' },
  { key: 'Accept', value: 'application/json' },
];
const lc = (name, method, path, body, query) => {
  const headers = [...authHeaders];
  if (body) headers.push({ key: 'Content-Type', value: 'application/json' });
  const segs = path.split('/').filter(Boolean);
  const req = {
    method,
    header: headers,
    url: {
      raw: '{{base_url}}/' + segs.join('/') + (query ? '?' + query.map((q) => q.key + '=' + q.value).join('&') : ''),
      host: ['{{base_url}}'],
      path: segs,
      ...(query ? { query } : {}),
    },
  };
  if (body) req.body = { mode: 'raw', raw: JSON.stringify(body, null, 4), options: { raw: { language: 'json' } } };
  return { name, request: req };
};
const ejemplo = {
  serie: 'L001',
  fecha_emision: '{{fecha_hoy}}',
  tipo_operacion: '0501',
  productos_agropecuarios: false,
  vendedor: { tipo_doc: '1', num_doc: '45678912', nombre: 'QUISPE MAMANI JUAN', direccion: 'CASERIO SAN JUAN S/N', ubigeo: '080101', tipo_domicilio: '02' },
  lugar: { direccion: 'CHACRA LOS ANDES KM 5', ubigeo: '080101', tipo: '02' },
  items: [{ codigo: 'CAFE01', cod_producto_sunat: '50131700', descripcion: 'Café pergamino', unidad: 'KGM', cantidad: 100, valor_unitario: 10, tip_afe_igv: '10' }],
  placa_vehiculo: 'ABC123',
};
const saveId = {
  listen: 'test',
  script: { type: 'text/javascript', exec: ["const j = pm.response.json();", "if (j.datos && j.datos.id) pm.collectionVariables.set('liquidacion_id', j.datos.id);"] },
};
const crear = lc('Crear liquidación de compra (compra interna 0501)', 'POST', 'liquidaciones-compra', ejemplo);
crear.event = [saveId];
const oro = JSON.parse(JSON.stringify(ejemplo));
oro.tipo_operacion = '0503';
oro.items = [{ codigo: 'ORO01', cod_producto_sunat: '11101700', descripcion: 'Oro en bruto', unidad: 'GRM', cantidad: 50, valor_unitario: 250, tip_afe_igv: '10',
  oro: { codigo_concesion: '010001234', ley_mineral: '0.85', naturaleza_mineral: '6000', nombre_derecho_minero: 'MINERA ARTESANAL SAN JUAN' } }];
delete oro.placa_vehiculo;
const carpetaLc = {
  name: '21. Liquidación de Compra (Tipo 04)',
  description: 'Comprobante emitido por el comprador a personas naturales sin RUC (art. 6 num. 1.3 del Reglamento de Comprobantes de Pago). Ver docs/LIQUIDACION_COMPRA.md.',
  item: [
    crear,
    lc('Crear liquidación de compra de oro (0503)', 'POST', 'liquidaciones-compra', oro),
    lc('Listar liquidaciones', 'GET', 'liquidaciones-compra', null, [{ key: 'per_page', value: '15' }, { key: 'sunat_status', value: 'aceptado' }]),
    lc('Ver liquidación', 'GET', 'liquidaciones-compra/{{liquidacion_id}}'),
    lc('Enviar a SUNAT', 'POST', 'liquidaciones-compra/{{liquidacion_id}}/enviar'),
    lc('Reenviar a SUNAT', 'POST', 'liquidaciones-compra/{{liquidacion_id}}/reenviar'),
    lc('Descargar XML', 'GET', 'liquidaciones-compra/{{liquidacion_id}}/xml'),
    lc('Descargar CDR', 'GET', 'liquidaciones-compra/{{liquidacion_id}}/cdr'),
    lc('Descargar PDF', 'GET', 'liquidaciones-compra/{{liquidacion_id}}/pdf', null, [{ key: 'formato', value: 'a4' }]),
    lc('Registrar pago al vendedor', 'POST', 'liquidaciones-compra/{{liquidacion_id}}/pagos', { pagos: [{ metodo: 'transferencia', monto: 985, referencia: 'OP-000123' }] }),
    lc('Listar pagos', 'GET', 'liquidaciones-compra/{{liquidacion_id}}/pagos'),
    lc('Eliminar pago', 'DELETE', 'liquidaciones-compra/{{liquidacion_id}}/pagos/{{pago_id}}'),
    lc('Reversión (RR)', 'POST', 'liquidaciones-compra/{{liquidacion_id}}/reversion', { causal: 'vendedor_documento', motivo: 'Documento del vendedor errado' }),
    lc('Reporte de retenciones de renta', 'GET', 'liquidaciones-compra/reporte-retenciones', null, [{ key: 'periodo', value: '{{periodo}}' }]),
  ],
};
const fechaHoy = {
  listen: 'prerequest',
  script: { type: 'text/javascript', exec: ["pm.variables.set('fecha_hoy', new Date(Date.now() - 5 * 3600 * 1000).toISOString().slice(0, 10)); // hoy en Lima (UTC-5)"] },
};
for (const it of carpetaLc.item.slice(0, 2)) it.event = [fechaHoy, ...(it.event || [])];
for (const it of carpetaLc.item) {
  const r = routes.find((x) => x.method === it.request.method && x.re.test(pathOf(it.request)));
  if (!r) throw new Error('Ruta de liquidación no existe en el OpenAPI: ' + it.request.method + ' ' + pathOf(it.request));
  used.add(r.method + ' ' + r.path);
}
const idx = col.item.findIndex((f) => /^23\./.test(f.name));
col.item.splice(idx < 0 ? col.item.length : idx, 0, carpetaLc);

// Rutas de esta API sin petición en la colección original
const conHost = (it) => {
  it.request.url.raw = it.request.url.raw.replace('{{base_url}}/', '{{host}}/');
  it.request.url.host = ['{{host}}'];
  return it;
};
const sinAuth = (it) => {
  it.request.header = it.request.header.filter((h) => !/^X-Api-/.test(h.key));
  return it;
};
const bridge = (it) => {
  sinAuth(conHost(it)).request.header.push({ key: 'X-Bridge-Key', value: '{{bridge_key}}' });
  return it;
};
const extras = [
  ['19.', [lc('Descargar PDF de percepción', 'GET', 'percepciones/{{percepcion_id}}/pdf', null, [{ key: 'formato', value: 'a4' }])]],
  ['23.', [
    lc('Producto SUNAT: segmentos', 'GET', 'catalogos/producto-sunat/segmentos'),
    lc('Producto SUNAT: familias de un segmento', 'GET', 'catalogos/producto-sunat/familias', null, [{ key: 'segmento', value: '50' }]),
    lc('Producto SUNAT: clases de una familia', 'GET', 'catalogos/producto-sunat/clases', null, [{ key: 'familia', value: '5013' }]),
    lc('Producto SUNAT: productos de una clase', 'GET', 'catalogos/producto-sunat/productos', null, [{ key: 'clase', value: '501317' }]),
  ]],
];
for (const [prefijo, items] of extras) col.item.find((f) => f.name.startsWith(prefijo)).item.push(...items);
col.item.unshift({
  name: '00. Sistema',
  description: 'Salud de la API (sin autenticación) y estado de los servicios SUNAT.',
  item: [
    sinAuth(conHost(lc('Salud de la API', 'GET', 'api/health'))),
    lc('Estado de SUNAT (circuit breaker)', 'GET', 'sunat/estado'),
  ],
});
col.item.push({
  name: '24. Alias /api/sunat y bridge SaaS',
  description: 'Rutas compatibles de API-PRO bajo /api/sunat y el bridge del SaaS principal (cabecera X-Bridge-Key = SAAS_BRIDGE_SECRET).',
  item: [
    conHost(lc('Alias: configuración de la empresa', 'GET', 'api/sunat/configuracion')),
    conHost(lc('Alias: listar facturas', 'GET', 'api/sunat/facturas')),
    conHost(lc('Alias: listar boletas', 'GET', 'api/sunat/boletas')),
    conHost(lc('Alias: XML de factura', 'GET', 'api/sunat/facturas/{{factura_id}}/xml')),
    conHost(lc('Alias: PDF de factura', 'GET', 'api/sunat/facturas/{{factura_id}}/pdf')),
    bridge(lc('Bridge: token de acceso', 'POST', 'api/bridge/auth', { email: 'usuario@miempresa.pe', name: 'Usuario Demo' })),
    bridge(lc('Bridge: aprovisionar empresa', 'POST', 'api/bridge/provision', {
      email: 'usuario@miempresa.pe', name: 'Usuario Demo', ruc: '20100000001', razon_social: 'MI EMPRESA S.A.C.', sol_user: 'MODDATOS', sol_pass: 'MODDATOS' })),
    bridge(lc('Bridge: eliminar empresa', 'DELETE', 'api/bridge/tenant', { ruc: '20100000001' })),
  ],
});
const urlPath = (it) => '/' + it.request.url.raw.replace(/^\{\{base_url\}\}/, 'api/v1').replace(/^\{\{host\}\}\//, '').split('?')[0];
for (const it of [...extras.flatMap((e) => e[1]), ...col.item[0].item, ...col.item[col.item.length - 1].item]) {
  const p = urlPath(it).replace(/\{\{[^}]+\}\}/g, 'x');
  const r = routes.find((x) => x.method === it.request.method && x.re.test(p));
  if (!r) throw new Error('Ruta extra no existe en el OpenAPI: ' + it.request.method + ' ' + p);
  used.add(r.method + ' ' + r.path);
}

// Identidad, URL y datos reales fuera
delete col.info._postman_id;
delete col.info._exporter_id;
delete col.info._collection_link;
col.info.name = 'API SUNAT · Spring Boot';
col.info.description = col.info.description
  .replace('API SUNAT PRO', 'la API SUNAT (Spring Boot, equivalente a API-PRO)')
  .replace(/`base_url` → [^\n]*/, '`base_url` → `http://localhost:8081/api/v1` (Docker local)')
  .replace(/\*\*Documentación completa\*\*[^\n]*/, '**Documentación**: README.md y Swagger en `http://localhost:8081/api/docs`. Sin SIRE (fase posterior), cotizaciones ni notas de venta (fuera de alcance).');
col.variable = [
  { key: 'host', value: 'http://localhost:8081' },
  { key: 'base_url', value: 'http://localhost:8081/api/v1' },
  { key: 'bridge_key', value: '' },
  { key: 'factura_id', value: '' },
  { key: 'percepcion_id', value: '' },
  { key: 'api_key', value: '' },
  { key: 'api_secret', value: '' },
  { key: 'ruc_empresa', value: '20000000001' },
  { key: 'periodo', value: '202610' },
  { key: 'num_ticket', value: '' },
  { key: 'liquidacion_id', value: '' },
  { key: 'pago_id', value: '' },
];
// El ejemplo de traslado entre establecimientos (motivo 04) usa una empresa real como emisor y
// destinatario: se reemplaza por la empresa de ejemplo de la colección
(function anonimizar(items) {
  for (const it of items) {
    if (it.item) { anonimizar(it.item); continue; }
    const body = it.request.body;
    for (const f of (body && (body.formdata || body.urlencoded)) || []) {
      if (f.key === 'client_id') f.value = 'TU_CLIENT_ID_GRE';
      if (f.key === 'client_secret') f.value = 'TU_CLIENT_SECRET_GRE';
    }
    if (!body || typeof body.raw !== 'string' || !/"cod_traslado":\s*"04"/.test(body.raw)) continue;
    body.raw = body.raw
      .replace(/("destinatario":\s*\{[^}]*?"razon_social":\s*)"[^"]*"/, '$1"MI EMPRESA S.A.C."')
      .replace(/("(?:num_doc|partida_ruc|llegada_ruc)":\s*)"\d{11}"/g, '$1"{{ruc_empresa}}"');
  }
})(col.item);
let text = JSON.stringify(col, null, '\t');
// Credenciales GRE de ejemplo y dominio de producción de API-PRO
text = text
  .replace(/(\\"client_id\\":\s*\\")[^"\\]+/g, '$1TU_CLIENT_ID_GRE')
  .replace(/(\\"client_secret\\":\s*\\")[^"\\]+/g, '$1TU_CLIENT_SECRET_GRE')
  .replace(/https:\/\/[a-z0-9.-]+(\/api\/v1)/g, 'http://localhost:8081$1');
fs.writeFileSync(out, text + '\n');

let n = 0;
(function cnt(it) { for (const i of it) { if (i.item) cnt(i.item); else n++; } })(col.item);
console.log('peticiones', n);
console.log('descartadas', dropped.length);
for (const d of dropped) console.log('  - ' + d);
const sin = routes.filter((r) => !used.has(r.method + ' ' + r.path) && r.path.startsWith('/api/'));
console.log('rutas del OpenAPI sin petición en la colección', sin.length);
for (const r of sin) console.log('  * ' + r.method + ' ' + r.path);
