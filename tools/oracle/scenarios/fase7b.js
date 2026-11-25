// Escenario de la Fase 7B: panel, reportes (JSON y PDF), exportación ZIP, búsqueda de documento y
// consulta de CPE. Reutiliza comprobantes que API-PRO ya acepta (escenarios de las fases 3 y 4) y
// los reparte por SQL entre fechas, estados, clientes, productos y sucursales.
//
// Uso (desde spring-boot/):
//   node tools/oracle/scenarios/fase7b.js
//   php tools/oracle/oracle.php src/test/resources/fixtures/oracle/scenario_fase7b.json src/test/resources/fixtures/oracle/fase7b.json
const fs = require('fs');
const path = require('path');
const fx = path.join(__dirname, '../../../src/test/resources/fixtures/oracle');
const fase4 = JSON.parse(fs.readFileSync(path.join(fx, 'scenario_fase4.json'), 'utf8'));
const factBol = JSON.parse(fs.readFileSync(path.join(fx, 'scenario_fact_bol.json'), 'utf8'));

const T = "(SELECT id FROM tenants WHERE ruc = '20000000001')";
const steps = [];
const sql = (name, ...q) => steps.push({ name, sql: q.join(';\n') });
const get = (name, url) => steps.push({ name, method: 'GET', url: '/api/v1' + url });
const post = (name, url, body) => steps.push({ name, method: 'POST', url: '/api/v1' + url, body });
const file = (name, p, content) => steps.push({ name, file: p, content });
const doc = (table, serie, corr) => `tenant_id = ${T} AND serie = '${serie}' AND correlativo = ${corr}`;
const upd = (table, set, serie, corr) => `UPDATE ${table} SET ${set} WHERE ${doc(table, serie, corr)}`;

// Comprobantes: facturas F001-1..6, boletas B001-1..4, notas FC01/BC01/FD01/BD01 (fase 4)
for (let i = 0; i <= 25; i++) steps.push(fase4.steps[i]);
// F001-7 crédito con cuotas, F001-8 exportación en USD, F001-9 gratuita, F001-10 ICBPER
for (const i of [2, 4, 6, 12]) steps.push(factBol.steps[i]);

post('Sucursal norte', '/sucursales', { nombre: 'SUCURSAL NORTE', cod_local: '0001', direccion: 'AV. NORTE 100', ubigeo: '150101' });
post('Sucursal sur', '/sucursales', { nombre: 'SUCURSAL SUR', cod_local: '0002', direccion: 'AV. SUR 200', ubigeo: '150101' });
post('Cliente registrado', '/clientes', { tipo_documento: '6', numero_documento: '20100000002', razon_social: 'CLIENTE REGISTRADO S.A.C.',
  nombre_comercial: 'REGISTRADO', direccion: 'AV. LOS OLIVOS 123', email: 'cliente@example.com', telefono: '999888777' });
post('Cliente DNI registrado', '/clientes', { tipo_documento: '1', numero_documento: '45678912', razon_social: 'MARIA LOPEZ QUISPE' });

sql('Estados SUNAT',
  `UPDATE invoices SET sunat_status = 'aceptado', sunat_code = '0' WHERE tenant_id = ${T}`,
  upd('invoices', "sunat_code = '4252', sunat_description = 'El dato ingresado como atributo @listName es incorrecto.'", 'F001', 5),
  upd('invoices', "sunat_status = 'rechazado', sunat_code = '2800', sunat_description = 'El dato ingresado en el tipo de documento de identidad del receptor no esta permitido.'", 'F001', 6),
  upd('invoices', "sunat_status = 'anulado'", 'F001', 10),
  `UPDATE boletas SET sunat_status = 'aceptado', sunat_code = '0' WHERE tenant_id = ${T} AND correlativo IN (1, 2)`,
  upd('boletas', "sunat_status = 'rechazado', sunat_code = '2017', sunat_description = 'El numero de documento de identidad del receptor debe ser RUC'", 'B001', 3),
  `UPDATE credit_notes SET sunat_status = 'aceptado', sunat_code = '0' WHERE tenant_id = ${T}`,
  upd('credit_notes', "sunat_status = 'rechazado', sunat_code = '2116', sunat_description = 'El tipo de documento modificado por la Nota de credito debe ser factura electronica o ticket'", 'FC01', 5),
  `UPDATE debit_notes SET sunat_status = 'aceptado', sunat_code = '0' WHERE tenant_id = ${T} AND correlativo <> 4`);

sql('Fechas de emisión',
  upd('invoices', "fecha_emision = '2026-10-23 09:00:00'", 'F001', 1),
  upd('invoices', "fecha_emision = '2026-10-20 11:00:00'", 'F001', 2),
  upd('invoices', "fecha_emision = '2026-09-15 10:00:00'", 'F001', 3),
  upd('invoices', "fecha_emision = '2025-10-10 10:00:00'", 'F001', 4),
  upd('boletas', "fecha_emision = '2026-05-05 12:00:00'", 'B001', 1),
  upd('credit_notes', "fecha_emision = '2026-09-16 10:00:00'", 'FC01', 2));

sql('Clientes, productos y sucursales',
  `UPDATE invoices SET client_num_doc = '20100000002', client_razon_social = 'CLIENTE REGISTRADO S.A.C.' WHERE tenant_id = ${T} AND serie = 'F001' AND correlativo IN (2, 7)`,
  `UPDATE boletas SET client_num_doc = '45678912', client_razon_social = 'MARIA LOPEZ QUISPE' WHERE tenant_id = ${T} AND correlativo = 4`,
  `UPDATE invoice_items SET descripcion = 'Servicio de instalación', codigo = 'S1' WHERE invoice_id = (SELECT id FROM invoices WHERE ${doc('invoices', 'F001', 2)})`,
  `UPDATE invoice_items SET descripcion = 'Mantenimiento preventivo', codigo = 'S2' WHERE invoice_id = (SELECT id FROM invoices WHERE ${doc('invoices', 'F001', 5)})`,
  `UPDATE invoices SET cod_local = '0001', sucursal_id = (SELECT id FROM sucursales WHERE tenant_id = ${T} AND cod_local = '0001') WHERE tenant_id = ${T} AND correlativo IN (1, 2, 7)`,
  `UPDATE invoices SET cod_local = '0002', sucursal_id = (SELECT id FROM sucursales WHERE tenant_id = ${T} AND cod_local = '0002') WHERE tenant_id = ${T} AND correlativo = 8`,
  `UPDATE boletas SET cod_local = '0002', sucursal_id = (SELECT id FROM sucursales WHERE tenant_id = ${T} AND cod_local = '0002') WHERE tenant_id = ${T} AND correlativo IN (1, 2)`,
  `UPDATE credit_notes SET cod_local = '0001' WHERE ${doc('credit_notes', 'FC01', 2)}`);

sql('Vencimientos (sin caer en los bordes de los tramos)',
  upd('invoices', "fecha_vencimiento = '2026-05-01'", 'F001', 1),
  upd('invoices', "fecha_vencimiento = '2026-10-27'", 'F001', 7),
  upd('invoices', "fecha_vencimiento = '2026-09-04'", 'F001', 8),
  upd('invoices', "fecha_vencimiento = '2026-07-01'", 'F001', 9),
  upd('boletas', "fecha_vencimiento = '2026-10-14'", 'B001', 2));
// Orden total por created_at (documentos recientes): un segundo distinto por fila
const creados = [];
let s = 0;
const ts = () => { s++; return `2026-10-24 14:${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`; };
for (const [table, serie, n] of [['invoices', 'F001', 10], ['boletas', 'B001', 4], ['credit_notes', 'FC01', 6], ['credit_notes', 'BC01', 1],
  ['debit_notes', 'FD01', 6], ['debit_notes', 'BD01', 1]]) {
  for (let c = 1; c <= n; c++) creados.push(upd(table, `created_at = '${ts()}'`, serie, c));
}
sql('Fechas de registro', ...creados);

post('Pago parcial F001-1', '/facturas/1/pagos', { metodo: 'efectivo', monto: 100, referencia: 'CAJA-1' });
post('Pago parcial F001-7', '/facturas/7/pagos', { metodo: 'yape', monto: 20.5, referencia: 'YP-778', notas: 'Adelanto' });
post('Pago parcial B001-2', '/boletas/2/pagos', { metodo: 'transferencia', monto: 5 });
sql('Pago total F001-2', upd('invoices', "monto_pagado = mto_imp_venta, payment_status = 'pagado'", 'F001', 2));
sql('Serie por agotarse', `UPDATE series SET correlativo = 90000001 WHERE tenant_id = ${T} AND serie = 'FD01'`);

// XML y PDF guardados (exportación ZIP)
const archivos = [['invoices', 'F001', 1, '2026-10-23', 'facturas'], ['invoices', 'F001', 2, '2026-10-20', 'facturas'],
  ['boletas', 'B001', 1, '2026-05-05', 'boletas'], ['credit_notes', 'FC01', 2, '2026-09-16', 'notas_credito']];
for (const [table, serie, c, fecha, carpeta] of archivos) {
  const base = `20000000001/0000/${fecha}/${carpeta}`;
  sql(`Rutas ${serie}-${c}`, upd(table, `xml_path = '${base}/xml/${serie}-${c}.xml', pdf_path = '${base}/pdf/${serie}-${c}.pdf'`, serie, c));
  file(`XML ${serie}-${c}`, `${base}/xml/${serie}-${c}.xml`, `<?xml version="1.0" encoding="UTF-8"?><Invoice><ID>${serie}-${c}</ID></Invoice>`);
  if (c !== 2) file(`PDF ${serie}-${c}`, `${base}/pdf/${serie}-${c}.pdf`, `%PDF-1.4 ${serie}-${c}`);
}

// ---------------------------------------------------------------- panel
get('Panel del mes', '/panel');
get('Panel de septiembre', '/panel?mes=2026-09');
get('Panel por rango', '/panel?desde=2026-10-19&hasta=2026-10-24');
get('Indicadores', '/panel/indicadores');
get('Estado SUNAT', '/panel/estado-sunat');
get('Estado SUNAT sin documentos', '/panel/estado-sunat?mes=2026-01');
get('Cobranzas', '/panel/cobranzas');
get('Ventas mensuales', '/panel/ventas-mensuales');
get('Por sucursal', '/panel/por-sucursal');
get('Por moneda', '/panel/por-moneda');
get('Clientes', '/panel/clientes');
get('Productos', '/panel/productos');
get('Documentos recientes', '/panel/documentos-recientes');
get('Documentos recientes (5)', '/panel/documentos-recientes?limit=5');
get('Alertas', '/panel/alertas');

// ---------------------------------------------------------------- reportes
// fecha_hasta = mañana: API-PRO compara fecha_emision (con hora) contra la fecha sola y deja
// fuera los comprobantes del último día; esta API lo corrige (ver DIFERENCIAS)
const R = 'fecha_desde=2026-01-01&fecha_hasta=2026-10-25';
get('Registro de ventas', `/reportes/registro-ventas?${R}`);
get('Registro de ventas filtrado', `/reportes/registro-ventas?${R}&serie=F001&estado_sunat=aceptado&tipo_moneda=PEN`);
get('Registro de ventas por sucursal', `/reportes/registro-ventas?${R}&sucursal_id=1`);
get('Ventas consolidado', `/reportes/ventas-consolidado?${R}`);
get('Ventas consolidado por día', `/reportes/ventas-consolidado?${R}&agrupacion=dia`);
get('Ventas consolidado por semana', `/reportes/ventas-consolidado?${R}&agrupacion=semana`);
get('Notas', `/reportes/notas?${R}`);
get('Cobranzas', `/reportes/cobranzas?${R}`);
get('Cobranzas vencidas', `/reportes/cobranzas?${R}&vencido=1`);
get('Cobranzas parciales', `/reportes/cobranzas?${R}&estado_pago=parcial`);
get('Estado de cuenta', `/reportes/por-cliente?${R}&client_num_doc=20100000002`);
get('Estado de cuenta (no registrado)', `/reportes/por-cliente?${R}&client_num_doc=20601234567`);
get('Estado de cuenta sin cliente', `/reportes/por-cliente?${R}`);
get('Por sucursal', `/reportes/por-sucursal?${R}`);
get('Por sucursal (una)', `/reportes/por-sucursal?${R}&sucursal_id=2`);
get('Reporte sin fechas', '/reportes/registro-ventas');
get('Reporte rango invertido', '/reportes/registro-ventas?fecha_desde=2026-10-24&fecha_hasta=2026-10-01');
get('Reporte de más de un año', '/reportes/notas?fecha_desde=2025-01-01&fecha_hasta=2026-10-24');
get('Reporte con filtros inválidos', `/reportes/cobranzas?${R}&estado_pago=debe&tipo_moneda=EUR&formato=xls&agrupacion=anio&serie=F1`);
for (const [n, u] of [['Registro de ventas', 'registro-ventas'], ['Ventas consolidado', 'ventas-consolidado'], ['Notas', 'notas'],
  ['Cobranzas', 'cobranzas'], ['Por sucursal', 'por-sucursal']]) {
  get(`${n} (PDF)`, `/reportes/${u}?${R}&formato=pdf`);
}
get('Estado de cuenta (PDF)', `/reportes/por-cliente?${R}&client_num_doc=20100000002&formato=pdf`);
get('Registro de ventas filtrado (PDF)', `/reportes/registro-ventas?${R}&serie=F001&estado_sunat=aceptado&formato=pdf`);

// ---------------------------------------------------------------- exportación
get('ZIP de XML', '/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-10-24');
get('ZIP de XML y PDF', '/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-10-24&tipo=ambos');
get('ZIP de PDF de facturas aceptadas', '/comprobantes/exportar-zip?fecha_desde=2026-10-01&fecha_hasta=2026-10-24&tipo=pdf&documentos=facturas&estado=aceptado');
get('ZIP por sucursal', '/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-10-24&sucursal_id=1&documentos=facturas, notas-credito');
get('ZIP sin fechas', '/comprobantes/exportar-zip?fecha_desde=2026-01-01');
get('ZIP fecha inválida', '/comprobantes/exportar-zip?fecha_desde=2026-13-45&fecha_hasta=2026-10-24');
get('ZIP rango invertido', '/comprobantes/exportar-zip?fecha_desde=2026-10-24&fecha_hasta=2026-10-01');
get('ZIP de más de un año', '/comprobantes/exportar-zip?fecha_desde=2025-01-01&fecha_hasta=2026-10-24');
get('ZIP tipo inválido', '/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-10-24&tipo=zip');
get('ZIP documentos inválidos', '/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-10-24&documentos=guias,cotizaciones');
get('ZIP sin archivos', '/comprobantes/exportar-zip?fecha_desde=2026-02-01&fecha_hasta=2026-02-28');

// ---------------------------------------------------------------- búsqueda de documento y consulta de CPE
get('Buscar cliente registrado', '/buscar-documento?tipo=6&numero=20100000002');
get('Buscar RUC en SUNAT', '/buscar-documento?tipo=6&numero=20100000009');
get('Buscar DNI en RENIEC', '/buscar-documento?tipo=1&numero=45678913');
get('Buscar DNI con nombre completo', '/buscar-documento?tipo=1&numero=45678914');
get('Buscar RUC inexistente', '/buscar-documento?tipo=6&numero=20100000010');
get('Buscar RUC sin razón social', '/buscar-documento?tipo=6&numero=20100000011');
get('Buscar carné de extranjería', '/buscar-documento?tipo=4&numero=X00000001');
get('Buscar sin tipo', '/buscar-documento?numero=20100000002');
get('Buscar número corto', '/buscar-documento?tipo=6&numero=123');
get('Consultar CPE sin datos', '/consultar-cpe');
get('Consultar CPE fecha ISO', '/consultar-cpe?tipo_doc=01&serie=F001&correlativo=1&fecha_emision=2026-10-24&monto=1180');
get('Consultar CPE sin credenciales', '/consultar-cpe?tipo_doc=01&serie=F001&correlativo=1&fecha_emision=24/10/2026&monto=1180');

const LOOKUP = 'https://api.apis.net.pe/v1';
const scenario = {
  now: '2026-10-24T15:15:30Z',
  tenant: Object.fromEntries(Object.entries(fase4.tenant).filter(([k]) => k !== 'cert_pem')),
  series: fase4.series,
  // Las vistas de reportes leen claves que el servicio no entrega (en API-PRO esos PDF dan 500)
  view_fixes: { 'pdf/reports': { "['sunat_status']": "['estado_sunat']", "['payment_status']": "['estado_pago']" } },
  http_fakes: [
    { url: `${LOOKUP}/ruc?numero=20100000009`, body: { razonSocial: 'PROVEEDOR CONSULTADO S.A.C.', direccion: 'JR. UNION 456 LIMA', estado: 'ACTIVO', condicion: 'HABIDO' } },
    { url: `${LOOKUP}/dni?numero=45678913`, body: { data: { nombres: 'ROSA', apellidoPaterno: 'QUISPE', apellidoMaterno: 'MAMANI' } } },
    { url: `${LOOKUP}/dni?numero=45678914`, body: { nombreCompleto: 'CARLOS ANDRES RAMOS DIAZ', nombres: 'CARLOS ANDRES', apellido_paterno: 'RAMOS', apellido_materno: 'DIAZ', direccion: 'CALLE 1' } },
    { url: `${LOOKUP}/ruc?numero=20100000011`, body: { estado: 'ACTIVO' } },
  ],
  steps,
};
fs.writeFileSync(path.join(fx, 'scenario_fase7b.json'), JSON.stringify(scenario, null, 1) + '\n');
console.log('pasos', steps.length);
