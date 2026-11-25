package pe.facturacion.api.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Lector CSV mínimo compatible con {@code fgetcsv} (comillas dobles y comas internas). */
final class CsvReader {

    private CsvReader() {
    }

    /** Lee el siguiente registro o null al final (un campo entre comillas puede abarcar líneas). */
    static List<String> next(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        while (true) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            cur.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            if (!quoted) {
                break;
            }
            String more = reader.readLine();
            if (more == null) {
                break;
            }
            cur.append('\n');
            line = more;
        }
        fields.add(cur.toString());
        return fields;
    }
}
