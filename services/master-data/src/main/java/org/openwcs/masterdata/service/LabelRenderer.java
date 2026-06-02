package org.openwcs.masterdata.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.openwcs.masterdata.domain.LabelElement;
import org.openwcs.masterdata.domain.LabelTemplate;
import org.springframework.stereotype.Service;

/**
 * Renders a {@link LabelTemplate} plus a map of field values into a concrete print payload.
 * ZPL is generated for thermal label printers; PDF is a minimal single-page document. Element
 * values come from the static {@code value} or, failing that, the supplied data by {@code key};
 * BARCODE/IMAGE elements are drawn as a barcode (ZPL) or their text (PDF) for now.
 */
@Service
public class LabelRenderer {

    public enum Format { ZPL, PDF }

    public record Rendered(String format, byte[] payload) {
    }

    public Rendered render(LabelTemplate template, Map<String, String> data, Format format) {
        byte[] payload = format == Format.PDF ? renderPdf(template, data) : renderZpl(template, data);
        return new Rendered(format.name(), payload);
    }

    private static String resolve(LabelElement e, Map<String, String> data) {
        if (e.value() != null && !e.value().isBlank()) {
            return e.value();
        }
        return e.key() == null ? null : data.get(e.key());
    }

    // --------------------------------------------------------------------------- ZPL
    private byte[] renderZpl(LabelTemplate t, Map<String, String> data) {
        int dpi = t.getDpi() <= 0 ? 203 : t.getDpi();
        StringBuilder z = new StringBuilder("^XA\n");
        for (LabelElement e : t.getElements()) {
            String value = resolve(e, data);
            if (value == null || value.isBlank()) {
                continue;
            }
            int x = dots(e.xMm(), dpi);
            int y = dots(e.yMm(), dpi);
            z.append("^FO").append(x).append(',').append(y);
            if ("BARCODE".equals(e.type())) {
                int h = e.heightMm() > 0 ? dots(e.heightMm(), dpi) : dots(15, dpi);
                z.append("^BY2^BCN,").append(h).append(",Y,N,N");
                z.append("^FD").append(zplEscape(value)).append("^FS\n");
            } else {
                int font = dots(e.fontPt() == null ? 3.0 : e.fontPt() * 25.4 / 72.0, dpi);
                z.append("^A0N,").append(font).append(',').append(font);
                z.append("^FD").append(zplEscape(value)).append("^FS\n");
            }
        }
        z.append("^XZ");
        return z.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int dots(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    private static String zplEscape(String s) {
        return s.replace("^", " ").replace("~", " ");
    }

    // --------------------------------------------------------------------------- PDF
    private byte[] renderPdf(LabelTemplate t, Map<String, String> data) {
        double w = pt(t.getWidthMm().doubleValue());
        double h = pt(t.getHeightMm().doubleValue());
        StringBuilder content = new StringBuilder();
        for (LabelElement e : t.getElements()) {
            String value = resolve(e, data);
            if (value == null || value.isBlank()) {
                continue;
            }
            double x = pt(e.xMm());
            double y = h - pt(e.yMm()); // PDF origin is bottom-left
            double font = e.fontPt() == null ? 10.0 : e.fontPt();
            content.append("BT /F1 ").append(fmt(font)).append(" Tf ")
                    .append(fmt(x)).append(' ').append(fmt(y)).append(" Td (")
                    .append(pdfEscape(value)).append(") Tj ET\n");
        }
        return buildPdf(w, h, content.toString());
    }

    private static double pt(double mm) {
        return mm * 72.0 / 25.4;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pdfEscape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\n", " ");
    }

    /** Assembles a valid minimal single-page PDF with a correct xref table. */
    private static byte[] buildPdf(double widthPt, double heightPt, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[5];
        write(out, "%PDF-1.4\n");

        offsets[0] = out.size();
        write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets[1] = out.size();
        write(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 "
                + fmt(widthPt) + " " + fmt(heightPt) + "] >>\nendobj\n");
        offsets[2] = out.size();
        write(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> "
                + "/Contents 5 0 R >>\nendobj\n");
        offsets[3] = out.size();
        write(out, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);
        offsets[4] = out.size();
        write(out, "5 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        out.writeBytes(contentBytes);
        write(out, "\nendstream\nendobj\n");

        int xrefStart = out.size();
        write(out, "xref\n0 6\n");
        write(out, "0000000000 65535 f \n");
        for (int off : offsets) {
            write(out, String.format(Locale.ROOT, "%010d 00000 n \n", off));
        }
        write(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xrefStart + "\n%%EOF");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
