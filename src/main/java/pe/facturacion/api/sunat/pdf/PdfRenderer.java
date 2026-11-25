package pe.facturacion.api.sunat.pdf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.xhtmlrenderer.css.style.CalculatedStyle;
import org.xhtmlrenderer.layout.breaker.BreakPoint;
import org.xhtmlrenderer.layout.breaker.BreakPointsProvider;
import org.xhtmlrenderer.layout.breaker.ListBreakPointsProvider;
import org.xhtmlrenderer.pdf.ITextRenderer;

/**
 * {@code PdfGeneratorService::generate()}: plantilla del formato (Thymeleaf, XHTML) con las
 * secciones del tipo de documento, convertida a PDF con Flying Saucer sobre OpenPDF. Las plantillas
 * en {@code templates/pdf} son el port de las vistas Blade {@code resources/views/pdf} de API-PRO.
 */
public final class PdfRenderer {

    private static final TemplateEngine ENGINE = engine();

    private PdfRenderer() {
    }

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/pdf/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.XML);
        r.setCharacterEncoding("UTF-8");
        r.setCacheable(true);
        TemplateEngine e = new TemplateEngine();
        e.setTemplateResolver(r);
        return e;
    }

    /** XHTML de la representación impresa (útil para revisar el contenido sin generar el PDF). */
    public static String html(Map<String, Object> data, PdfFormat format) {
        return html(data, format, format.pageSize);
    }

    private static String html(Map<String, Object> data, PdfFormat format, String pageSize) {
        Context ctx = new Context();
        ctx.setVariables(data);
        ctx.setVariable("h", PdfHelper.INSTANCE);
        ctx.setVariable("format", format.value);
        ctx.setVariable("page_size", pageSize);
        ctx.setVariable("is_ticket", format.isTicket());
        ctx.setVariable("sections", PdfDocumentTypes.get(String.valueOf(data.get("tipo_documento"))).sections());
        return ENGINE.process(format.template, ctx);
    }

    /**
     * Cortes de línea solo después de un espacio o de un guion entre caracteres, como dompdf (motor de API-PRO). El de Flying
     * Saucer también corta tras "." y "/" (pensado para URL) y partía "S.A.C." o "00/100" en
     * columnas angostas.
     */
    static BreakPointsProvider spaceBreaks(String text, String lang, CalculatedStyle style) {
        List<BreakPoint> points = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean space = Character.isWhitespace(c) && (i + 1 == text.length() || !Character.isWhitespace(text.charAt(i + 1)));
            boolean hyphen = c == '-' && i > 0 && i + 1 < text.length() && !Character.isWhitespace(text.charAt(i - 1))
                    && !Character.isWhitespace(text.charAt(i + 1));
            if (space || hyphen) {
                points.add(new BreakPoint(i + 1));
            }
        }
        if (points.isEmpty() || points.get(points.size() - 1).getPosition() != text.length()) {
            points.add(new BreakPoint(text.length()));
        }
        points.add(BreakPoint.getDonePoint());
        return new ListBreakPointsProvider(points);
    }

    /** Alto máximo de una página PDF (14400 pt, límite de Acrobat). */
    static final float MAX_PAGE_PT = 14400f;
    /** Márgenes verticales del ticket ({@code @page { margin: 5mm 4mm }}) más un respiro. */
    static final float TICKET_MARGINS_PT = 2 * 5 * 72f / 25.4f + 4f;

    public static byte[] render(Map<String, Object> data, PdfFormat format) {
        if (!format.isTicket()) {
            return toPdf(html(data, format));
        }
        // Mejora (M1): el ticket mide lo que su contenido. API-PRO usaba siempre el alto de un A4
        // (841.89 pt) y la impresora térmica avanzaba papel en blanco. Primera pasada en una página
        // muy alta para medir el contenido; la segunda, con ese alto.
        String ancho = format.pageSize.split(" ")[0];
        try {
            ITextRenderer medida = renderer(html(data, format, ancho + " " + MAX_PAGE_PT + "pt"));
            float altoPt = medida.getRootBox().getHeight() / medida.getDotsPerPoint() + TICKET_MARGINS_PT;
            if (altoPt < MAX_PAGE_PT) {
                return toPdf(html(data, format, ancho + " " + String.format(java.util.Locale.ROOT, "%.2f", altoPt) + "pt"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el PDF: " + e.getMessage(), e);
        }
        return toPdf(html(data, format));
    }

    private static ITextRenderer renderer(String xhtml) {
        ITextRenderer renderer = new ITextRenderer();
        renderer.getSharedContext().setLineBreakingStrategy(PdfRenderer::spaceBreaks);
        renderer.setDocumentFromString(xhtml);
        renderer.layout();
        return renderer;
    }

    /** PDF de otra plantilla de {@code templates/pdf} (p. ej. los reportes), con {@code h} disponible. */
    public static byte[] renderTemplate(String template, Map<String, Object> vars) {
        Context ctx = new Context();
        ctx.setVariables(vars);
        ctx.setVariable("h", PdfHelper.INSTANCE);
        return toPdf(ENGINE.process(template, ctx));
    }

    private static byte[] toPdf(String xhtml) {
        try {
            ITextRenderer renderer = renderer(xhtml);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.createPDF(out);
            renderer.finishPDF();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el PDF: " + e.getMessage(), e);
        }
    }
}
