package pe.facturacion.api.sunat.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * Código QR de la representación impresa como imagen PNG embebida ({@code data:} URI). API-PRO
 * genera un SVG de 150 px con BaconQrCode (corrección L, margen 4); aquí el mismo contenido,
 * corrección y margen en PNG (el motor de PDF no dibuja SVG).
 */
public final class QrImage {

    private QrImage() {
    }

    public static String dataUri(String content) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L, EncodeHintType.MARGIN, 4,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el código QR", e);
        }
    }
}
