package pe.facturacion.api.shared.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AppProperties;

/**
 * Discos locales como los de Laravel ({@code Storage::disk('local'|'public')}), bajo {@code app.storage-path}:
 * <ul>
 *   <li>{@link Disk#PRIVATE} → {@code {storage}/app/private}: XML, CDR, PDF, certificados cifrados. Nunca se sirve.</li>
 *   <li>{@link Disk#PUBLIC} → {@code {storage}/app/public}: logos, servidos en {@code /storage/**} ({@code WebMvcConfig}).</li>
 * </ul>
 * Las rutas son relativas al disco; una ruta absoluta o que salga del disco ({@code ..}) se rechaza.
 * Los errores de disco se lanzan como {@link UncheckedIOException}.
 *
 * <p>Reconstruido el 2026-11-23: el paquete no venía en la copia del proyecto porque la regla {@code storage/}
 * del {@code .gitignore} (sin anclar) también excluía {@code src/.../shared/storage/}.</p>
 */
@Component
public class FileStorage {

    public enum Disk {
        PRIVATE("app/private"),
        PUBLIC("app/public");

        private final String folder;

        Disk(String folder) {
            this.folder = folder;
        }
    }

    private final Path privateRoot;
    private final Path publicRoot;
    private final String appUrl;

    public FileStorage(AppProperties props) {
        String base = props.storagePath() == null || props.storagePath().isBlank() ? "./storage" : props.storagePath();
        Path root = Path.of(base).toAbsolutePath().normalize();
        this.privateRoot = root.resolve(Disk.PRIVATE.folder).normalize();
        this.publicRoot = root.resolve(Disk.PUBLIC.folder).normalize();
        this.appUrl = props.url() == null ? "" : props.url().replaceAll("/+$", "");
    }

    /** Carpeta raíz del disco (se crea si no existe). */
    public Path diskRoot(Disk disk) {
        Path root = disk == Disk.PRIVATE ? privateRoot : publicRoot;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el disco " + disk, e);
        }
        return root;
    }

    /** Ruta absoluta de un archivo del disco; rechaza rutas absolutas o que salgan del disco. */
    public Path resolve(Disk disk, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Ruta de archivo vacía.");
        }
        String clean = relativePath.replace('\\', '/');
        if (clean.startsWith("/") || clean.contains("\0") || clean.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Ruta de archivo no permitida.");
        }
        Path root = diskRoot(disk);
        Path target = root.resolve(clean).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Ruta de archivo no permitida.");
        }
        return target;
    }

    public boolean exists(Disk disk, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        return Files.isRegularFile(resolve(disk, relativePath));
    }

    public byte[] get(Disk disk, String relativePath) {
        try {
            return Files.readAllBytes(resolve(disk, relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo.", e);
        }
    }

    /**
     * Escribe el archivo de forma atómica (temporal + renombrar): un lector nunca ve un XML o PDF a medias.
     * En el disco privado, con POSIX, el archivo queda solo para el usuario del proceso (600).
     */
    public void put(Disk disk, String relativePath, byte[] content) {
        Path target = resolve(disk, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            try {
                Files.write(temp, content == null ? new byte[0] : content);
                if (disk == Disk.PRIVATE) {
                    ownerOnly(temp);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo.", e);
        }
    }

    public void delete(Disk disk, String relativePath) {
        try {
            Files.deleteIfExists(resolve(disk, relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar el archivo.", e);
        }
    }

    /** Borra una carpeta del disco con todo su contenido (no hace nada si no existe). */
    public void deleteDirectory(Disk disk, String relativePath) {
        Path dir = resolve(disk, relativePath);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar la carpeta.", e);
        }
    }

    /** URL pública de un archivo del disco {@code public}: {@code {APP_URL}/storage/{ruta}} ({@code Storage::url}). */
    public String url(String relativePath) {
        resolve(Disk.PUBLIC, relativePath);
        return appUrl + "/storage/" + relativePath.replace('\\', '/');
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows u otros sistemas sin POSIX: se usan los permisos por defecto de la carpeta.
        }
    }
}
