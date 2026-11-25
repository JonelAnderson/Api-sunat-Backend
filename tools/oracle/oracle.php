<?php
/**
 * ORÁCULO DE API-PRO (solo lectura).
 *
 * Ejecuta un escenario contra el código real de API-PRO (Laravel 12): BD SQLite en memoria con
 * sus migraciones, kernel HTTP real (middlewares, FormRequests, controladores), colas y correo
 * falsos, HTTP saliente simulado y reloj fijo. Guarda la respuesta de cada paso para que las
 * pruebas de paridad de esta API la comparen (src/test/resources/fixtures/oracle).
 *
 * Uso: php tools/oracle/oracle.php escenario.json salida.json
 *
 * Escenario: {now, tenant{...api_key, api_secret, cert_pem?}, series[], http_fakes[], steps[]}
 * Pasos:
 *   {name, method, url, body?}        petición HTTP autenticada con las credenciales del tenant
 *   {name, sql}                       sentencias SQL (separadas por ";"), válidas en SQLite y PostgreSQL
 *   {name, file, content}             crea un archivo de comprobante (disco de comprobantes)
 * Respuestas: {name, status, body} · PDF: {pdf_base64, headers} · ZIP: {zip[{name, sha1}], headers}
 */
[$_, $scenarioFile, $outFile] = $argv;
$app = require __DIR__ . '/boot.php';

use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Mail;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Facades\View;

$sc = json_decode(file_get_contents($scenarioFile), true);
Artisan::call('migrate', ['--force' => true]);
Queue::fake();
Mail::fake();
Carbon::setTestNow(Carbon::parse($sc['now']));
\Carbon\CarbonImmutable::setTestNow(\Carbon\CarbonImmutable::parse($sc['now']));

// HTTP saliente: solo las respuestas declaradas; el resto falla con 404 (nada sale a internet)
$fakes = [];
foreach ($sc['http_fakes'] ?? [] as $f) {
    $fakes[$f['url']] = Http::response($f['body'] ?? '', $f['status'] ?? 200);
}
$fakes['*'] = Http::response('', 404);
Http::fake($fakes);

// SQLite: sin FK (dispatch_guides referencia una tabla que solo existe en MySQL) y
// whereJsonContains con la semántica de JSON_CONTAINS de MySQL
DB::statement('PRAGMA foreign_keys = OFF');
DB::connection()->setQueryGrammar(new class(DB::connection()) extends Illuminate\Database\Query\Grammars\SQLiteGrammar {
    protected function compileJsonContains($column, $value)
    {
        return 'mysql_json_contains(' . $this->wrap($column) . ', ' . $value . ')';
    }

    public function prepareBindingForJsonContains($binding)
    {
        return json_encode($binding);
    }
});
$jc = function ($t, $c) use (&$jc) {
    if (is_array($t) && array_is_list($t)) {
        if (is_array($c) && array_is_list($c)) {
            foreach ($c as $x) {
                $ok = false;
                foreach ($t as $y) {
                    if ($jc($y, $x)) { $ok = true; break; }
                }
                if (!$ok) return false;
            }
            return true;
        }
        foreach ($t as $y) {
            if ($jc($y, $c)) return true;
        }
        return false;
    }
    if (is_array($t) && is_array($c) && !array_is_list($c)) {
        foreach ($c as $k => $v) {
            if (!array_key_exists($k, $t) || !$jc($t[$k], $v)) return false;
        }
        return true;
    }
    if (is_array($t) || is_array($c)) return false;
    return (is_int($t) || is_float($t)) && (is_int($c) || is_float($c)) ? $t == $c : $t === $c;
};
// Funciones de MySQL que usa API-PRO en SQL crudo
$pdo = DB::connection()->getPdo();
$pdo->sqliteCreateFunction('mysql_json_contains', fn ($t, $c) => $t === null || $c === null ? null
    : (int) $jc(json_decode($t, true), json_decode($c, true)), 2);
$pdo->sqliteCreateFunction('DATEDIFF', fn ($a, $b) => $a === null || $b === null ? null
    : (int) round((strtotime(substr($a, 0, 10)) - strtotime(substr($b, 0, 10))) / 86400), 2);
$pdo->sqliteCreateFunction('DATE_FORMAT', fn ($d, $f) => $d === null ? null
    : date(strtr($f, ['%Y' => 'Y', '%m' => 'm', '%d' => 'd', '%H' => 'H', '%i' => 'i', '%s' => 's']), strtotime($d)), 2);

// Correcciones mínimas a vistas de API-PRO que fallan siempre (p. ej. claves inexistentes): se
// copian a tools/oracle/storage/views con los reemplazos declarados y se anteponen a las originales
foreach ($sc['view_fixes'] ?? [] as $dir => $fixes) {
    $src = base_path('resources/views/' . $dir);
    $dst = __DIR__ . '/storage/views/' . $dir;
    @mkdir($dst, 0777, true);
    foreach (glob("$src/*.blade.php") as $f) {
        file_put_contents("$dst/" . basename($f), strtr(file_get_contents($f), $fixes));
    }
    View::getFinder()->prependLocation(__DIR__ . '/storage/views');
    View::flushFinderCache();
}

(new Database\Seeders\PlanSeeder())->run();
(new Database\Seeders\UnspscCodeSeeder())->run();

// Empresa
$t = $sc['tenant'];
$tenant = new App\Models\Tenant();
$tenant->forceFill(array_merge([
    'environment' => 'beta', 'plan' => 'free', 'is_active' => true, 'tax_regime' => 'general',
], array_diff_key($t, ['cert_pem' => 1])));
$tenant->save();
if (!empty($t['cert_pem'])) {
    $path = "certificates/{$tenant->ruc}/cert.pem";
    Illuminate\Support\Facades\Storage::disk('local')->put($path, file_get_contents($t['cert_pem']));
    $tenant->forceFill(['certificate_path' => $path])->save();
}
foreach ($sc['series'] ?? [] as $s) {
    App\Models\Serie::create(['tenant_id' => $tenant->id, 'tipo_documento' => $s['tipo'], 'serie' => $s['serie'],
        'correlativo' => $s['correlativo'] ?? 0, 'is_active' => true]);
}

$kernel = $app->make(Illuminate\Contracts\Http\Kernel::class);
$results = [];
foreach ($sc['steps'] as $step) {
    if (isset($step['sql'])) {
        foreach (array_filter(array_map('trim', explode(';', $step['sql']))) as $q) {
            DB::statement($q);
        }
        $results[] = ['name' => $step['name'], 'sql' => true];
        continue;
    }
    if (isset($step['file'])) {
        App\Support\Storage\Comprobantes::put($step['file'], $step['content']);
        $results[] = ['name' => $step['name'], 'file' => true];
        continue;
    }
    $server = [
        'HTTP_X_API_KEY' => $t['api_key'], 'HTTP_X_API_SECRET' => $t['api_secret'],
        'HTTP_ACCEPT' => 'application/json', 'CONTENT_TYPE' => 'application/json', 'REMOTE_ADDR' => '127.0.0.1',
    ];
    $body = isset($step['body']) ? json_encode($step['body'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null;
    // createFromBase: igual que Request::capture() en una petición real (copia el JSON al bag "request")
    $request = Illuminate\Http\Request::createFromBase(
        Symfony\Component\HttpFoundation\Request::create($step['url'], $step['method'], [], [], [], $server, $body));
    $response = $kernel->handle($request);
    if ($response instanceof Symfony\Component\HttpFoundation\StreamedResponse) {
        // streamDownload hace ob_flush(): el búfer interno pasa al externo y ambos se recogen
        ob_start();
        ob_start();
        $response->sendContent();
        $inner = ob_get_clean();
        $content = ob_get_clean() . $inner;
    } else {
        $content = $response->getContent();
    }
    $kernel->terminate($request, $response);

    $type = (string) $response->headers->get('Content-Type');
    $entry = ['name' => $step['name'], 'status' => $response->getStatusCode(), 'body' => $content];
    $headers = ['content-type' => $type, 'content-disposition' => $response->headers->get('Content-Disposition'),
        'cache-control' => $response->headers->get('Cache-Control')];
    if (str_contains($type, 'application/pdf')) {
        $entry['body'] = null;
        $entry['pdf_base64'] = base64_encode($content);
        $entry['headers'] = $headers;
    } elseif (str_contains($type, 'application/zip')) {
        $tmp = tempnam(sys_get_temp_dir(), 'oraculo');
        file_put_contents($tmp, $content);
        $zip = new ZipArchive();
        $zip->open($tmp);
        $entries = [];
        for ($i = 0; $i < $zip->numFiles; $i++) {
            $entries[] = ['name' => $zip->getNameIndex($i), 'sha1' => sha1($zip->getFromIndex($i))];
        }
        $zip->close();
        unlink($tmp);
        $entry['body'] = null;
        $entry['zip'] = $entries;
        $entry['headers'] = $headers;
    }
    $results[] = $entry;
    // Columnas DATE: MySQL/PostgreSQL guardan solo la fecha; SQLite guardaría "Y-m-d H:i:s"
    foreach (['summaries' => ['fecha_envio', 'fecha_referencia'], 'voided_documents' => ['fecha_comunicacion', 'fecha_generacion']] as $tbl => $cols) {
        foreach ($cols as $c) {
            DB::statement("UPDATE {$tbl} SET {$c} = substr({$c}, 1, 10)");
        }
    }
    // Sin caché entre pasos: ni rate limit ni reportes cacheados de un paso anterior
    app('cache')->store()->flush();
}
file_put_contents($outFile, json_encode($results, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
echo 'OK ' . count($results) . " pasos\n";
