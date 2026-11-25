package pe.facturacion.api.sunat.ws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP de envío y lectura del CDR, como {@code Greenter\Zip\ZipFly} y {@code BaseSunat::getXmlResponse}.
 *
 * <p>El ZIP lleva una sola entrada {@code {nombre}.xml} comprimida con deflate, sin descriptor
 * de datos (flags 0), "made by" Unix 3.20 y atributos 0644.
 */
public final class SunatZip {

    private SunatZip() {
    }

    public static byte[] compress(String entryName, byte[] content, LocalDateTime now) {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(content);
        deflater.finish();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            deflated.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        byte[] data = deflated.toByteArray();
        int dosTime = (now.getHour() << 11) | (now.getMinute() << 5) | (now.getSecond() / 2);
        int dosDate = ((now.getYear() - 1980) << 9) | (now.getMonthValue() << 5) | now.getDayOfMonth();

        ByteBuffer local = le(30 + name.length);
        local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8)
                .putShort((short) dosTime).putShort((short) dosDate)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(content.length)
                .putShort((short) name.length).putShort((short) 0).put(name);

        ByteBuffer central = le(46 + name.length);
        central.putInt(0x02014b50).putShort((short) 0x0314).putShort((short) 20).putShort((short) 0).putShort((short) 8)
                .putShort((short) dosTime).putShort((short) dosDate)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(content.length)
                .putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0x81A4 << 16).putInt(0).put(name);

        int centralOffset = local.capacity() + data.length;
        ByteBuffer end = le(22);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1)
                .putInt(central.capacity()).putInt(centralOffset).putShort((short) 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(centralOffset + central.capacity() + 22);
        out.writeBytes(local.array());
        out.writeBytes(data);
        out.writeBytes(central.array());
        out.writeBytes(end.array());
        return out.toByteArray();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Primer archivo {@code .xml} del ZIP (ignora carpetas como {@code dummy/}); {@code null} si no hay. */
    public static byte[] firstXml(byte[] zip) {
        if (zip == null || zip.length == 0) {
            return null;
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
