package pe.facturacion.api.modules.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Comandos de operación (equivalentes a {@code php artisan ...} de API-PRO):
 *
 * <pre>
 * java -jar api-sunat.jar ops &lt;tarea&gt; [--opcion=valor]
 * docker compose run --rm api ops &lt;tarea&gt; [--opcion=valor]
 * </pre>
 *
 * Arranca la aplicación sin servidor web, sin el worker de la cola y sin el planificador, ejecuta la
 * tarea, imprime su reporte y termina (código 0 si todo está bien).
 */
public final class OpsCli {

    record Command(String descripcion, Function<Map<String, String>, List<String>> run) {
    }

    private OpsCli() {
    }

    static Map<String, Command> commands(OpsTasks t) {
        Map<String, Command> c = new LinkedHashMap<>();
        c.put("sunat:reintentar-pendientes", new Command("Reencola a SUNAT facturas y boletas atascadas en enviado (--minutos=15 --dias=7 --estados=enviado)",
                o -> t.reintentarPendientes(intOpt(o, "minutos", 15), intOpt(o, "dias", 7),
                        Arrays.stream(o.getOrDefault("estados", "enviado").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())));
        c.put("summaries:poll-pending", new Command("Vuelve a consultar el ticket de los resúmenes diarios sin estado final (--limit=100)",
                o -> t.pollResumenes(intOpt(o, "limit", 100))));
        c.put("logs:purge", new Command("Elimina api_logs con más de N días (--days=90)", o -> t.purgarLogs(intOpt(o, "days", 90))));
        c.put("suscripciones:renovar", new Command("Renueva las suscripciones vencidas (ProcessRecurringPayments)", o -> t.renovarSuscripciones()));
        c.put("suscripciones:vencer-pruebas", new Command("Avisa y vence las pruebas gratis (CheckTrialExpiration)", o -> t.vencerPruebas()));
        c.put("uso:reiniciar", new Command("Reinicia los contadores de uso del mes (ResetMonthlyUsage)", o -> t.reiniciarUso()));
        c.put("certificados:verificar", new Command("Verifica que cada empresa pueda firmar (--tenant=ID --alertar)",
                o -> t.verificarCertificados(o.containsKey("tenant") ? Long.valueOf(o.get("tenant")) : null, o.containsKey("alertar"))));
        c.put("ops:jobs-fallidos", new Command("Revisa failed_jobs (--alertar)", o -> t.revisarJobsFallidos(o.containsKey("alertar"))));
        c.put("jobs-fallidos:reintentar", new Command("Reencola los trabajos fallidos: todos o --id=N (queue:retry)",
                o -> t.reintentarJobsFallidos(o.containsKey("id") ? Long.valueOf(o.get("id")) : null)));
        c.put("ops:salud", new Command("Revisa BD, Redis, disco, almacenamiento y SUNAT (--alertar)", o -> t.revisarSalud(o.containsKey("alertar"))));
        return c;
    }

    /** {@code --clave=valor} y {@code --bandera}. */
    static Map<String, String> options(List<String> args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                o.put(eq < 0 ? a.substring(2) : a.substring(2, eq), eq < 0 ? "" : a.substring(eq + 1));
            }
        }
        return o;
    }

    static int intOpt(Map<String, String> o, String key, int def) {
        try {
            return o.containsKey(key) ? Integer.parseInt(o.get(key)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Ejecuta {@code args = [ops, tarea, opciones...]}; devuelve el código de salida. */
    public static int run(OpsTasks tasks, String[] args) {
        Map<String, Command> cmds = commands(tasks);
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        if (rest.isEmpty() || !cmds.containsKey(rest.get(0))) {
            System.out.println("Uso: ops <tarea> [--opcion=valor]\n\nTareas:");
            cmds.forEach((k, v) -> System.out.println("  " + String.format("%-30s", k) + v.descripcion()));
            return rest.isEmpty() ? 0 : 2;
        }
        try {
            for (String line : cmds.get(rest.get(0)).run().apply(options(rest.subList(1, rest.size())))) {
                System.out.println(line);
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
