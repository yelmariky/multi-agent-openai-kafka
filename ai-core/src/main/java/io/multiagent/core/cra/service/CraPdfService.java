package io.multiagent.core.cra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.cra.entity.CraEntity;
import io.multiagent.core.cra.repository.CraJpaRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.CraDayEntry;
import io.multiagent.core.model.SellerProfile;
import io.multiagent.core.organization.entity.Client;
import io.multiagent.core.organization.entity.Mission;
import io.multiagent.core.organization.entity.Resource;
import io.multiagent.core.organization.entity.ProjectEntity;
import io.multiagent.core.organization.entity.ConsultantAssignmentEntity;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.organization.repository.MissionRepository;
import io.multiagent.core.organization.repository.ProjectRepository;
import io.multiagent.core.service.WeaviateService;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CraPdfService {

    private final CraJpaRepository craRepo;
    private final MissionRepository missionRepo;
    private final ProjectRepository projectRepo;
    private final ConsultantAssignmentRepository assignmentRepo;
    private final WeaviateService weaviateService;
    private final ObjectMapper objectMapper;
    private final ConsultantProfileJpaRepository consultantProfileRepo;

    private static final float MARGIN = 30;
    private static final float LEADING = 14f;

    // Colors
    private static final Color COLOR_HEADER_BG = new Color(0x2c, 0x3e, 0x50);
    private static final Color COLOR_HEADER_TEXT = Color.WHITE;
    private static final Color COLOR_TRAVAIL = new Color(0xd5, 0xf5, 0xe3);
    private static final Color COLOR_ABSENT = new Color(0xfc, 0xe4, 0xec);
    private static final Color COLOR_FERIE = new Color(0xff, 0xf3, 0xcd);
    private static final Color COLOR_WEEKEND = new Color(0xe8, 0xe8, 0xe8);

    @Transactional(readOnly = true)
    public byte[] generatePdf(UUID craId, UUID projectIdFilter) {
        CraEntity cra = craRepo.findById(craId)
                .orElseThrow(() -> new IllegalArgumentException("CRA introuvable : " + craId));

        UUID tenantId = TenantContext.getTenantId();
        if (!cra.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Acces interdit");
        }

        // Resolve mission data
        Mission mission = null;
        Resource resource = null;
        Client client = null;
        if (cra.getMissionId() != null) {
            mission = missionRepo.findById(cra.getMissionId()).orElse(null);
            if (mission != null) {
                resource = mission.getResource();
                client = mission.getClient();
            }
        }

        // Resolve project data
        ProjectEntity project = null;
        if (cra.getProjectId() != null) {
            project = projectRepo.findById(cra.getProjectId()).orElse(null);
        }

        // Consultant profile — cra.getConsultant() stores the email (since frontend sends user.email)
        ConsultantProfileEntity consultantProfile = null;
        if (cra.getConsultant() != null) {
            consultantProfile = consultantProfileRepo
                    .findByTenantIdAndEmailIgnoreCase(tenantId, cra.getConsultant())
                    .orElse(null);
        }

        // Display name for PDF header: full name from profile, fallback to raw consultant field
        String consultantDisplayName = (consultantProfile != null && consultantProfile.getName() != null)
                ? consultantProfile.getName() : cra.getConsultant();

        // Fallback row label (for mono-projet / rétro-compat)
        String fallbackLabel = resolveRowLabel(project, mission, consultantProfile);

        // Prestataire contact: validatedBy (admin who validated) > resource name > consultant name
        String prestaContact;
        if (cra.getValidatedBy() != null && !cra.getValidatedBy().isBlank()) {
            prestaContact = safe(cra.getValidatedBy());
        } else if (resource != null && resource.getName() != null) {
            prestaContact = safe(resource.getName());
        } else {
            prestaContact = consultantDisplayName;
        }

        // Résolution assignment (TRIO projet/client/TJM configuré par l'admin)
        // Si projectIdFilter est fourni (PDF filtré par projet), on cherche l'assignment de ce projet précis.
        // Sinon : projectId du CRA > premier assignment du consultant
        ConsultantAssignmentEntity assignment = null;
        if (consultantProfile != null) {
            List<ConsultantAssignmentEntity> assignments =
                    assignmentRepo.findByConsultantProfileIdAndTenantId(consultantProfile.getId(), tenantId);
            UUID effectivePid = projectIdFilter != null ? projectIdFilter : cra.getProjectId();
            if (effectivePid != null && !assignments.isEmpty()) {
                assignment = assignments.stream()
                        .filter(a -> a.getProject() != null
                                && a.getProject().getId().equals(effectivePid))
                        .findFirst()
                        .orElseGet(() -> assignments.get(0));
            } else if (!assignments.isEmpty()) {
                assignment = assignments.get(0);
            }
        }

        // Client block: mission client > assignment client (TRIO) > champs CRA (stale) > profil consultant
        String clientSociete;
        String clientAddr;
        String clientContact;
        Client resolvedClient = client != null ? client
                : (assignment != null ? assignment.getClient() : null);

        if (resolvedClient != null) {
            clientSociete = safe(resolvedClient.getName());
            clientAddr    = safe(resolvedClient.getAddress());
            // Préférer contactName, sinon contactEmail
            String cn = resolvedClient.getContactName();
            String ce = resolvedClient.getContactEmail();
            clientContact = (cn != null && !cn.isBlank()) ? cn : safe(ce);
        } else {
            // Dernier recours : valeurs sauvegardées dans le CRA ou le profil consultant
            String craClientCompany = cra.getClientCompany();
            clientSociete = (craClientCompany != null && !craClientCompany.isBlank()) ? craClientCompany
                    : (consultantProfile != null ? safe(consultantProfile.getClientName()) : "");
            clientAddr = consultantProfile != null ? safe(consultantProfile.getClientAddress()) : "";
            String craEmail = cra.getClientContactEmail();
            clientContact = (craEmail != null && !craEmail.isBlank()) ? craEmail
                    : (consultantProfile != null ? safe(consultantProfile.getClientContactEmail()) : "");
        }

        // Seller profile (prestataire company info)
        SellerProfile seller = weaviateService.findSellerProfile(cra.getCompany());

        // Parse entries
        List<CraDayEntry> entries = parseEntries(cra.getEntriesJson());

        // Parse month
        YearMonth ym = YearMonth.parse(cra.getBillingMonth());
        int daysInMonth = ym.lengthOfMonth();
        String monthLabel = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE)
                + " " + ym.getYear();

        try (PDDocument doc = new PDDocument()) {
            PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            PDPage page = new PDPage(landscape);
            doc.addPage(page);

            float pageW = landscape.getWidth();
            float pageH = landscape.getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = pageH - MARGIN;

                // === HEADER ===
                y = drawHeader(cs, y, pageW, consultantDisplayName, monthLabel, cra.getStatus(),
                        cra.getValidatedBy(), cra.getValidatedAt());

                // === INFO BLOCKS (Prestataire | Client) ===
                y = drawInfoBlocks(cs, y, pageW, seller, prestaContact, clientSociete, clientAddr, clientContact);

                // === ACTIVITY GRID (multi-projets) ===
                // Grouper les entrées par projectId.
                // Les entrées de type ABSENT ou projectId "__ABSENCE__" → groupe dédié "Absences" (affiché en premier).
                Map<String, List<CraDayEntry>> byProject = new LinkedHashMap<>();
                for (CraDayEntry e : entries) {
                    String pid;
                    if ("ABSENT".equals(e.type()) || "__ABSENCE__".equals(e.projectId())) {
                        pid = "__ABSENCE__";
                    } else {
                        pid = (e.projectId() != null && !e.projectId().isBlank())
                                ? e.projectId() : "__fallback__";
                    }
                    byProject.computeIfAbsent(pid, k -> new ArrayList<>()).add(e);
                }
                if (byProject.isEmpty()) {
                    byProject.put("__fallback__", entries.isEmpty() ? new ArrayList<>() : new ArrayList<>(entries));
                }
                // Filtre par projet si demandé (PDF par client) : garder uniquement le projet ciblé + absences
                if (projectIdFilter != null) {
                    byProject.entrySet().removeIf(entry ->
                        !"__ABSENCE__".equals(entry.getKey()) &&
                        !projectIdFilter.toString().equals(entry.getKey())
                    );
                }
                // Résoudre le label de chaque groupe — ligne Absences en premier.
                // Ignorer les groupes sans entrées de travail ou d'absence (ex. résidu WEEKEND/FERIE d'anciens CRAs).
                List<ProjectRow> rows = new ArrayList<>();
                ProjectRow absenceRow = null;
                for (Map.Entry<String, List<CraDayEntry>> grp : byProject.entrySet()) {
                    String pid = grp.getKey();
                    boolean hasMeaningfulData = grp.getValue().stream()
                            .anyMatch(e -> "TRAVAIL".equals(e.type()) || "ABSENT".equals(e.type()));
                    if (!hasMeaningfulData) continue;

                    String label;
                    if ("__ABSENCE__".equals(pid)) {
                        label = "Absences";
                    } else if ("__fallback__".equals(pid)) {
                        label = fallbackLabel;
                    } else {
                        label = fallbackLabel;
                        try {
                            label = projectRepo.findById(UUID.fromString(pid))
                                    .map(p -> safeText(p.getName()))
                                    .orElse(fallbackLabel);
                        } catch (IllegalArgumentException ex) {
                            log.debug("UUID projet invalide dans CRA PDF : {}", pid);
                        }
                    }
                    ProjectRow pr = new ProjectRow(label, grp.getValue());
                    if ("__ABSENCE__".equals(pid)) {
                        absenceRow = pr;
                    } else {
                        rows.add(pr);
                    }
                }
                if (absenceRow != null) rows.add(0, absenceRow);
                y -= 8;
                y = drawActivityGrid(cs, y, pageW, rows, daysInMonth);

                // === TOTAL ===
                y -= 10;
                // Total = jours travaillés uniquement (ABSENT exclu — non facturable).
                // PDF filtré : calcul depuis les lignes filtrées. PDF complet : entrées TRAVAIL du CRA entier.
                double total;
                if (projectIdFilter != null) {
                    total = rows.stream()
                            .flatMap(r -> r.entries().stream())
                            .filter(e -> "TRAVAIL".equals(e.type()))
                            .mapToDouble(CraDayEntry::value)
                            .sum();
                } else {
                    total = entries.stream()
                            .filter(e -> "TRAVAIL".equals(e.type()))
                            .mapToDouble(CraDayEntry::value)
                            .sum();
                }
                cs.setFont(PDType1Font.HELVETICA_BOLD, 11);
                writeLine(cs, MARGIN, y, "Total : " + formatDays(total) + " jours");
                y -= LEADING;

                // === LEGEND ===
                y -= 6;
                y = drawLegend(cs, y);

                // === SIGNATURE BLOCK ===
                y -= 20;
                y = drawSignatureBlock(cs, y, pageW);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("CRA PDF generation failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Echec generation PDF CRA : " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Drawing methods
    // -----------------------------------------------------------------------

    private float drawHeader(PDPageContentStream cs, float y, float pageW,
                             String consultant, String monthLabel, String status,
                             String validatedBy, String validatedAt) throws Exception {
        // Title bar background
        cs.setNonStrokingColor(COLOR_HEADER_BG);
        cs.addRect(MARGIN, y - 30, pageW - 2 * MARGIN, 32);
        cs.fill();

        cs.setNonStrokingColor(COLOR_HEADER_TEXT);
        cs.setFont(PDType1Font.HELVETICA_BOLD, 14);
        writeLine(cs, MARGIN + 10, y - 22, "RAPPORT D'ACTIVITE");

        // Month + name right-aligned
        String rightText = safeText(consultant) + " - " + capitalize(monthLabel);
        float rightW = PDType1Font.HELVETICA_BOLD.getStringWidth(safeText(rightText)) / 1000 * 14;
        writeLine(cs, pageW - MARGIN - rightW - 10, y - 22, safeText(rightText));

        cs.setNonStrokingColor(Color.BLACK);
        y -= 40;

        // Status line
        if (status != null && !status.isBlank()) {
            cs.setFont(PDType1Font.HELVETICA, 9);
            String statusLine = "Statut : " + status;
            if ("VALIDE".equals(status) && validatedBy != null) {
                statusLine += " par " + validatedBy;
                if (validatedAt != null && !validatedAt.isBlank()) {
                    statusLine += " le " + validatedAt.substring(0, Math.min(10, validatedAt.length()));
                }
            }
            writeLine(cs, MARGIN, y, safeText(statusLine));
            y -= LEADING;
        }

        return y;
    }

    private float drawInfoBlocks(PDPageContentStream cs, float y, float pageW,
                                  SellerProfile seller, String prestaContact,
                                  String clientSociete, String clientAddr, String clientContact) throws Exception {
        float midX = pageW / 2;
        float blockH = 72;
        float startY = y - 4;

        // Draw boxes
        cs.setStrokingColor(new Color(200, 200, 200));
        cs.setLineWidth(0.5f);
        cs.addRect(MARGIN, startY - blockH, midX - MARGIN - 5, blockH);
        cs.stroke();
        cs.addRect(midX + 5, startY - blockH, pageW - midX - MARGIN - 5, blockH);
        cs.stroke();

        // Prestataire block
        float infoY = startY - 14;
        cs.setFont(PDType1Font.HELVETICA_BOLD, 9);
        writeLine(cs, MARGIN + 6, infoY, "PRESTATAIRE");
        infoY -= LEADING;
        cs.setFont(PDType1Font.HELVETICA, 9);

        String prestaSociete = seller != null && seller.companyName() != null ? seller.companyName() : "";
        writeLine(cs, MARGIN + 6, infoY, "Societe : " + safeText(prestaSociete));
        infoY -= LEADING - 2;

        String prestaAddr = seller != null && seller.address() != null ? seller.address() : "";
        writeLine(cs, MARGIN + 6, infoY, "Adresse : " + safeText(truncate(prestaAddr, 50)));
        infoY -= LEADING - 2;

        writeLine(cs, MARGIN + 6, infoY, "Contact : " + safeText(prestaContact));

        // Client block
        infoY = startY - 14;
        cs.setFont(PDType1Font.HELVETICA_BOLD, 9);
        writeLine(cs, midX + 11, infoY, "CLIENT");
        infoY -= LEADING;
        cs.setFont(PDType1Font.HELVETICA, 9);

        writeLine(cs, midX + 11, infoY, "Societe : " + safeText(clientSociete));
        infoY -= LEADING - 2;

        writeLine(cs, midX + 11, infoY, "Adresse : " + safeText(truncate(clientAddr, 50)));
        infoY -= LEADING - 2;

        writeLine(cs, midX + 11, infoY, "Contact : " + safeText(clientContact));

        return startY - blockH - 4;
    }

    record ProjectRow(String label, List<CraDayEntry> entries) {}

    private float drawActivityGrid(PDPageContentStream cs, float y, float pageW,
                                    List<ProjectRow> rows, int daysInMonth) throws Exception {
        float gridLeft = MARGIN;
        float labelW   = 160;
        float availW   = pageW - 2 * MARGIN - labelW;
        float cellW    = Math.min(availW / daysInMonth, 22);
        float cellH    = 20;
        int   rowCount = rows.size();

        // === Header row (day numbers) ===
        cs.setNonStrokingColor(COLOR_HEADER_BG);
        cs.addRect(gridLeft, y - cellH, labelW, cellH);
        cs.fill();
        for (int d = 1; d <= daysInMonth; d++) {
            cs.addRect(gridLeft + labelW + (d - 1) * cellW, y - cellH, cellW, cellH);
            cs.fill();
        }
        cs.setNonStrokingColor(COLOR_HEADER_TEXT);
        cs.setFont(PDType1Font.HELVETICA_BOLD, 7);
        writeLine(cs, gridLeft + 4, y - 13, "Projet / Type");
        for (int d = 1; d <= daysInMonth; d++) {
            float cx = gridLeft + labelW + (d - 1) * cellW + (cellW / 2) - 3;
            writeLine(cs, cx, y - 13, String.valueOf(d));
        }
        y -= cellH;

        // === Data rows (one per project) ===
        // Pré-calcul des totaux par jour — TRAVAIL uniquement (absences affichées mais non comptabilisées)
        double[] dayTotals = new double[daysInMonth + 1];
        for (ProjectRow row : rows) {
            cs.setFont(PDType1Font.HELVETICA, 7);
            cs.setNonStrokingColor(new Color(245, 245, 245));
            cs.addRect(gridLeft, y - cellH, labelW, cellH);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            writeLine(cs, gridLeft + 4, y - 13, safeText(row.label()));

            for (int d = 1; d <= daysInMonth; d++) {
                CraDayEntry entry = findEntry(row.entries(), d);
                float cellX = gridLeft + labelW + (d - 1) * cellW;
                Color bg    = Color.WHITE;
                String lbl  = "";
                if (entry != null) {
                    switch (entry.type()) {
                        case "WEEKEND" -> bg = COLOR_WEEKEND;
                        case "FERIE"   -> { bg = COLOR_FERIE;  lbl = "F"; }
                        // Absences : affichées dans la grille mais NON comptées dans le total
                        case "ABSENT"  -> { bg = COLOR_ABSENT; lbl = entry.value() < 1.0 ? "1/2" : "A"; }
                        default -> {
                            if (entry.value() >= 1.0)  { bg = COLOR_TRAVAIL; lbl = "1";   dayTotals[d] += entry.value(); }
                            else if (entry.value() > 0) { bg = COLOR_TRAVAIL; lbl = "0.5"; dayTotals[d] += entry.value(); }
                        }
                    }
                }
                cs.setNonStrokingColor(bg);
                cs.addRect(cellX, y - cellH, cellW, cellH);
                cs.fill();
                cs.setStrokingColor(new Color(180, 180, 180));
                cs.setLineWidth(0.3f);
                cs.addRect(cellX, y - cellH, cellW, cellH);
                cs.stroke();
                if (!lbl.isEmpty()) {
                    cs.setNonStrokingColor(Color.BLACK);
                    float tw = PDType1Font.HELVETICA.getStringWidth(lbl) / 1000 * 7;
                    writeLine(cs, cellX + (cellW - tw) / 2, y - 13, lbl);
                }
            }
            y -= cellH;
        }

        // === Ligne Total ===
        cs.setNonStrokingColor(COLOR_HEADER_BG);
        cs.addRect(gridLeft, y - cellH, labelW, cellH);
        cs.fill();
        cs.setNonStrokingColor(COLOR_HEADER_TEXT);
        cs.setFont(PDType1Font.HELVETICA_BOLD, 7);
        writeLine(cs, gridLeft + 4, y - 13, "Total");
        double grandTotal = 0;
        for (int d = 1; d <= daysInMonth; d++) {
            float cellX = gridLeft + labelW + (d - 1) * cellW;
            double val = dayTotals[d];
            grandTotal += val;
            String lbl = val == 0 ? "" : (val >= 1.0 ? String.valueOf((int) val) : "0.5");
            // Fond : vert si journée complète, jaune si demi, gris si 0
            Color bg = val == 0 ? new Color(230, 230, 230) : (val >= 1.0 ? COLOR_TRAVAIL : COLOR_FERIE);
            cs.setNonStrokingColor(bg);
            cs.addRect(cellX, y - cellH, cellW, cellH);
            cs.fill();
            cs.setStrokingColor(new Color(180, 180, 180));
            cs.setLineWidth(0.3f);
            cs.addRect(cellX, y - cellH, cellW, cellH);
            cs.stroke();
            if (!lbl.isEmpty()) {
                cs.setNonStrokingColor(Color.BLACK);
                cs.setFont(PDType1Font.HELVETICA_BOLD, 7);
                float tw = PDType1Font.HELVETICA_BOLD.getStringWidth(lbl) / 1000 * 7;
                writeLine(cs, cellX + (cellW - tw) / 2, y - 13, lbl);
            }
        }
        y -= cellH;

        // === Outer border (encadre header + toutes les lignes + total) ===
        float gridW = labelW + daysInMonth * cellW;
        cs.setStrokingColor(new Color(100, 100, 100));
        cs.setLineWidth(0.8f);
        cs.addRect(gridLeft, y, gridW, cellH * (2 + rowCount));
        cs.stroke();

        return y;
    }

    private float drawLegend(PDPageContentStream cs, float y) throws Exception {
        cs.setFont(PDType1Font.HELVETICA, 8);
        float x = MARGIN;
        float boxSize = 8;
        float gap = 6;

        String[][] legend = {
                {"1 / 0.5", "Travail"},
                {"A", "Absent"},
                {"F", "Ferie"},
                {"", "Weekend"}
        };
        Color[] colors = {COLOR_TRAVAIL, COLOR_ABSENT, COLOR_FERIE, COLOR_WEEKEND};

        for (int i = 0; i < legend.length; i++) {
            cs.setNonStrokingColor(colors[i]);
            cs.addRect(x, y - boxSize + 2, boxSize, boxSize);
            cs.fill();
            cs.setStrokingColor(new Color(150, 150, 150));
            cs.setLineWidth(0.3f);
            cs.addRect(x, y - boxSize + 2, boxSize, boxSize);
            cs.stroke();
            cs.setNonStrokingColor(Color.BLACK);
            writeLine(cs, x + boxSize + 3, y - 5, legend[i][1]);
            x += boxSize + 3 + PDType1Font.HELVETICA.getStringWidth(legend[i][1]) / 1000 * 8 + gap + 10;
        }

        return y - 14;
    }

    private float drawSignatureBlock(PDPageContentStream cs, float y, float pageW) throws Exception {
        cs.setStrokingColor(new Color(200, 200, 200));
        cs.setLineWidth(0.5f);
        float boxW = 300;
        float boxH = 60;
        float boxX = pageW - MARGIN - boxW;
        cs.addRect(boxX, y - boxH, boxW, boxH);
        cs.stroke();

        cs.setFont(PDType1Font.HELVETICA, 9);
        cs.setNonStrokingColor(Color.BLACK);
        float ty = y - 14;
        writeLine(cs, boxX + 8, ty, "Date :");
        ty -= LEADING;
        writeLine(cs, boxX + 8, ty, "Nom Client :");
        ty -= LEADING;
        writeLine(cs, boxX + 8, ty, "Signature Client :");

        return y - boxH - 10;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Row label priority: project name > mission title > first assigned project > default */
    private String resolveRowLabel(ProjectEntity project, Mission mission,
                                    ConsultantProfileEntity consultantProfile) {
        if (project != null && project.getName() != null) {
            return safeText(project.getName());
        }
        if (mission != null && mission.getTitle() != null) {
            return safeText(mission.getTitle());
        }
        if (consultantProfile != null && !consultantProfile.getProjects().isEmpty()) {
            return safeText(consultantProfile.getProjects().get(0).getName());
        }
        return "Activite normale";
    }

    private CraDayEntry findEntry(List<CraDayEntry> entries, int dayOfMonth) {
        if (entries == null) return null;
        String suffix = String.format("-%02d", dayOfMonth);
        return entries.stream()
                .filter(e -> e != null && e.date() != null && e.date().endsWith(suffix))
                .findFirst().orElse(null);
    }

    private List<CraDayEntry> parseEntries(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CraDayEntry.class));
        } catch (Exception e) {
            log.warn("Could not parse CRA entries JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void writeLine(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(safeText(text));
        cs.endText();
    }

    private String safeText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 32 && c <= 255) sb.append(c);
            else sb.append('?');
        }
        return sb.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        String v = safe(s);
        return v.length() <= max ? v : v.substring(0, max - 3) + "...";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String formatDays(double d) {
        if (d == Math.floor(d)) return String.valueOf((int) d);
        return String.format(Locale.FRANCE, "%.1f", d);
    }
}
