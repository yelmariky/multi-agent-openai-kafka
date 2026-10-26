package io.multiagent.core.policy.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Génère un PDF simple (titre + texte intégral, pagination automatique) à partir du texte d'une
 * politique saisie en texte libre — pour les politiques importées en PDF, le fichier original est
 * servi tel quel à la place (voir PolicyFileStorageService), ce rendu n'est jamais utilisé dans ce cas.
 */
@Service
@lombok.RequiredArgsConstructor
public class PolicyPdfRenderingService {

    private final io.multiagent.core.i18n.Messages messages;

    private static final float MARGIN = 50;
    private static final float LEADING = 16f;
    private static final float FONT_SIZE = 11f;
    private static final PDType1Font FONT = PDType1Font.HELVETICA;
    private static final PDType1Font FONT_BOLD = PDType1Font.HELVETICA_BOLD;

    public byte[] render(String title, int version, String rawText) {
        try (PDDocument doc = new PDDocument()) {
            PDRectangle a4 = PDRectangle.A4;
            float maxWidth = a4.getWidth() - 2 * MARGIN;

            PDPage page = new PDPage(a4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = a4.getHeight() - MARGIN;

            y = writeLine(cs, title + " — v" + version, FONT_BOLD, 14f, MARGIN, y);
            y = writeLine(cs, messages.get("policy.pdf.generatedOn", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(java.time.LocalDate.now())),
                    FONT, 9f, MARGIN, y - 4);
            y -= LEADING;

            for (String paragraph : rawText.split("\n")) {
                for (String line : wrap(paragraph, FONT, FONT_SIZE, maxWidth)) {
                    if (y < MARGIN + LEADING) {
                        cs.close();
                        page = new PDPage(a4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = a4.getHeight() - MARGIN;
                    }
                    y = writeLine(cs, line, FONT, FONT_SIZE, MARGIN, y);
                }
            }
            cs.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to generate the policy PDF", e);
        }
    }

    private float writeLine(PDPageContentStream cs, String text, PDType1Font font, float fontSize, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text.isEmpty() ? " " : text);
        cs.endText();
        return y - LEADING;
    }

    /** Découpe un paragraphe en lignes tenant dans {@code maxWidth}, mot par mot. */
    private List<String> wrap(String paragraph, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (paragraph.isBlank()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * fontSize > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }
}
