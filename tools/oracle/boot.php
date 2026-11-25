<?php
// Arranca la app Laravel de API-PRO en modo solo lectura: todo lo que escribe (caché de
// configuración, vistas compiladas, archivos) va a tools/oracle/storage, nunca a API-PRO.
$apiPro = getenv('API_PRO') ?: 'C:/laragon/www/API-PRO';
// Laravel solo reconoce como absolutas las rutas que empiezan con "/" o "\": sin la letra de
// unidad, Windows las resuelve contra la unidad actual
$h = preg_replace('#^[A-Za-z]:#', '', str_replace('\\', '/', __DIR__));
foreach (['storage/framework/cache', 'storage/framework/views', 'storage/logs', 'storage/app/private', 'storage/app/public'] as $d) {
    if (!is_dir("$h/$d")) {
        mkdir("$h/$d", 0777, true);
    }
}
foreach ([
    'APP_ENV' => 'testing', 'APP_DEBUG' => 'false', 'APP_LOCALE' => 'es', 'DB_CONNECTION' => 'sqlite', 'DB_DATABASE' => ':memory:',
    'CACHE_STORE' => 'array', 'QUEUE_CONNECTION' => 'sync', 'SESSION_DRIVER' => 'array', 'LOG_CHANNEL' => 'stderr',
    'APP_SERVICES_CACHE' => "$h/storage/services.php", 'APP_PACKAGES_CACHE' => "$h/storage/packages.php",
    'APP_CONFIG_CACHE' => "$h/storage/config.php", 'APP_ROUTES_CACHE' => "$h/storage/routes.php", 'APP_EVENTS_CACHE' => "$h/storage/events.php",
    'VIEW_COMPILED_PATH' => "$h/storage/framework/views", 'SENTRY_LARAVEL_DSN' => '', 'TELESCOPE_ENABLED' => 'false',
    // Proveedor de consulta RUC/DNI: valor ficticio (las respuestas se simulan con Http::fake)
    'APIS_NET_PE_TOKEN' => 'token-oraculo',
] as $k => $v) {
    putenv("$k=$v");
    $_ENV[$k] = $v;
    $_SERVER[$k] = $v;
}
require $apiPro . '/vendor/autoload.php';
$app = require $apiPro . '/bootstrap/app.php';
$app->useStoragePath($h . '/storage');
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
return $app;
