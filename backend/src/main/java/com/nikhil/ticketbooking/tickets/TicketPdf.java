package com.nikhil.ticketbooking.tickets;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.Event;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the e-ticket PDF. Its QR code holds a link to the check-in page with the
 * booking reference, so door staff can scan it with a phone.
 */
@Component
public class TicketPdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final Color BRAND = new Color(0x4a, 0x5c, 0xf0);
    private static final Color INK = new Color(0x22, 0x22, 0x22);
    private static final Color MUTED = new Color(0x77, 0x77, 0x77);
    private static final Color RULE = new Color(0xdd, 0xdd, 0xdd);

    private final String frontendUrl;

    public TicketPdf(AppProperties props) {
        this.frontendUrl = props.frontendUrl().replaceAll("/+$", "");
    }

    public String checkInUrl(String reference) {
        return frontendUrl + "/checkin?ref=" + reference;
    }

    public byte[] render(Booking booking) throws IOException {
        Event event = booking.getEvent();
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();

            // The ticket is a card near the top of the page.
            float x = 50, w = pageW - 100, top = pageH - 60, h = 330, bottom = top - h;
            PDImageXObject qr = LosslessFactory.createFromImage(doc, qrImage(checkInUrl(booking.getReference()), 360));

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // card outline and coloured header band
                cs.setStrokingColor(RULE);
                cs.setLineWidth(1);
                cs.addRect(x, bottom, w, h);
                cs.stroke();
                cs.setNonStrokingColor(BRAND);
                cs.addRect(x, top - 56, w, 56);
                cs.fill();
                text(cs, bold, 13, Color.WHITE, x + 24, top - 24, "TICKET BOOKING");
                text(cs, regular, 11, Color.WHITE, x + 24, top - 42, "E-ticket  |  Admits one");
                String ref = booking.getReference();
                text(cs, bold, 13, Color.WHITE, x + w - 24 - bold.getStringWidth(ref) / 1000 * 13, top - 32, ref);

                // event details on the left
                float tx = x + 24, ty = top - 96;
                ty = wrapped(cs, bold, 22, INK, tx, ty, event.getName(), w - 230, 2);
                ty -= 10;
                label(cs, regular, bold, tx, ty, "DATE", DATE.format(event.getEventDate()));
                label(cs, regular, bold, tx + 150, ty, "TIME", TIME.format(event.getEventDate()));
                ty -= 46;
                label(cs, regular, bold, tx, ty, "VENUE", event.getVenue());
                ty -= 46;
                label(cs, regular, bold, tx, ty, "SEAT", booking.getSeat().getSeatLabel());
                label(cs, regular, bold, tx + 150, ty, "PRICE", "INR " + price(event.getPrice()));
                ty -= 46;
                label(cs, regular, bold, tx, ty, "TICKET HOLDER", booking.getUser().getEmail());

                // QR code on the right
                float qrSize = 170;
                float qrX = x + w - 24 - qrSize, qrY = bottom + 60;
                cs.drawImage(qr, qrX, qrY, qrSize, qrSize);
                String scan = "Scan at the entrance";
                text(cs, regular, 10, MUTED, qrX + (qrSize - regular.getStringWidth(scan) / 1000 * 10) / 2, qrY - 14, scan);

                // footer inside the card
                cs.setStrokingColor(RULE);
                cs.moveTo(x, bottom + 34);
                cs.lineTo(x + w, bottom + 34);
                cs.stroke();
                text(cs, regular, 9, MUTED, x + 24, bottom + 14,
                        "Valid for one entry. Do not share this ticket: the first scan is the only one accepted.");
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private static void label(PDPageContentStream cs, PDFont regular, PDFont bold, float x, float y,
                              String label, String value) throws IOException {
        text(cs, regular, 8, MUTED, x, y, label);
        text(cs, bold, 13, INK, x, y - 16, value);
    }

    /** Draws text wrapped to maxWidth over at most maxLines lines (ending in "..." if cut); returns the y below it. */
    private static float wrapped(PDPageContentStream cs, PDFont font, float size, Color color, float x, float y,
                                 String text, float maxWidth, int maxLines) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : safe(text).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(font, size, candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > maxLines) {
            String last = String.join(" ", lines.subList(maxLines - 1, lines.size()));
            while (last.length() > 1 && width(font, size, last + "...") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines = new java.util.ArrayList<>(lines.subList(0, maxLines - 1));
            lines.add(last.stripTrailing() + "...");
        }
        for (String l : lines) {
            text(cs, font, size, color, x, y, l);
            y -= size * 1.2f;
        }
        return y;
    }

    private static float width(PDFont font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000 * size;
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, Color color, float x, float y,
                             String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, y);
        cs.showText(safe(s));
        cs.endText();
    }

    /** The built-in PDF fonts only cover basic Latin characters, so replace anything else. */
    static String safe(String s) {
        if (s == null) return "";
        String plain = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static String price(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static BufferedImage qrImage(String content, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            return img;
        } catch (WriterException e) {
            throw new IllegalStateException("Could not create QR code", e);
        }
    }
}
