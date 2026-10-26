package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.repository.SellerProfileJpaRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceService — immutabilité, numérotation, avoirs, archivage, TVA")
class InvoiceLifecycleTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SELLER = "IA-INSIGHT";

    @Mock InvoiceJpaRepository invoiceRepo;
    @Mock SellerProfileJpaRepository sellerProfileRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @TempDir Path storage;

    private InvoiceService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new InvoiceService(invoiceRepo, sellerProfileRepo, jdbcTemplate, storage.toString());
        TenantContext.set(TENANT, "testme");
        when(invoiceRepo.save(any())).thenAnswer(a -> {
            InvoiceEntity e = a.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static SimpleInvoiceRequest request(String invoiceName) {
        return new SimpleInvoiceRequest(invoiceName, null, "2026-10", SELLER, "33 rue de la Terrasse", "RCS Paris 1",
                "INFOGENE DIGITAL", "19 rue d'Orleans", "RCS Nanterre 2", "Prestation", 20.0,
                new BigDecimal("600"), null, new BigDecimal("0.20"), null, "EUR", null, null, null, null,
                "consultant@ia-insight.fr", "expert kafka", null, "FR12345678901", "FR98765432109");
    }

    private static InvoiceEntity existing(String name, String status) {
        InvoiceEntity e = new InvoiceEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(TENANT);
        e.setInvoiceName(name);
        e.setSellerCompanyName(SELLER);
        e.setBillingMonth("2026-10");
        e.setInvoiceDate(LocalDate.of(2026, 11, 1));
        e.setClientCompanyName("INFOGENE DIGITAL");
        e.setTotalHt(new BigDecimal("12000.00"));
        e.setTotalTtc(new BigDecimal("14400.00"));
        e.setVatRate(new BigDecimal("0.20"));
        e.setDaysCount(20);
        e.setDaysExact(new BigDecimal("20"));
        e.setUnitPriceHt(new BigDecimal("600"));
        e.setPaymentStatus(status);
        return e;
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void nouvelleFacture_numeroteeFichierParIdEtTvaImprimee() throws Exception {
        var generated = service.generate(request(null));

        assertThat(generated.invoice().invoiceName()).isEqualTo("F-202611-01");
        assertThat(generated.pdfPath().getFileName().toString()).matches("[0-9a-f-]{36}\\.pdf");
        assertThat(Files.exists(generated.pdfPath())).isTrue();
        assertThat(text(generated.pdfBytes()))
                .contains("FACTURE")
                .contains("TVA intracom. : FR12345678901")
                .contains("TVA intracom. : FR98765432109");
    }

    @Test
    void brouillonRegenere_memeIdMemeNumero() throws Exception {
        InvoiceEntity draft = existing("F-202611-01", "EN_ATTENTE");
        when(invoiceRepo.findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(TENANT, "F-202611-01", SELLER))
                .thenReturn(List.of(draft));

        service.generate(request("F-202611-01"));

        verify(invoiceRepo, never()).deleteAll(any());
        verify(invoiceRepo, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                e -> draft.getId().equals(e.getId()) && "F-202611-01".equals(e.getInvoiceName())));
    }

    @Test
    void factureEnvoyee_nonRegenerable() {
        when(invoiceRepo.findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(TENANT, "F-202611-01", SELLER))
                .thenReturn(List.of(existing("F-202611-01", "ENVOYEE")));

        assertThatThrownBy(() -> service.generate(request("F-202611-01")))
                .isInstanceOf(InvoiceService.InvoiceLockedException.class)
                .hasMessageContaining("avoir");
        verify(invoiceRepo, never()).save(any());
    }

    @Test
    void numeroJamaisReutiliseApresAnnulation() throws Exception {
        when(invoiceRepo.findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(TENANT, "2026-10", SELLER))
                .thenReturn(List.of(existing("F-202611-01", "ANNULEE"), existing("F-202611-02", "ENVOYEE")));

        assertThat(service.generate(request(null)).invoice().invoiceName()).isEqualTo("F-202611-03");
    }

    @Test
    void avoir_montantsNegatifsLienEtNumeroDedie() throws Exception {
        String prefix = "A-" + YearMonth.now().toString().replace("-", "") + "-";
        when(invoiceRepo.findByTenantIdAndInvoiceNameStartingWithIgnoreCase(eq(TENANT), anyString()))
                .thenReturn(List.of(existing(prefix + "01", "EMIS")));
        InvoiceEntity original = existing("F-202611-01", "ANNULEE");

        InvoiceEntity credit = service.issueCreditNote(original);

        assertThat(credit.getInvoiceType()).isEqualTo("AVOIR");
        assertThat(credit.getInvoiceName()).isEqualTo(prefix + "02");
        assertThat(credit.getCreditedInvoiceId()).isEqualTo(original.getId());
        assertThat(credit.getTotalHt()).isEqualByComparingTo("-12000.00");
        assertThat(credit.getTotalTtc()).isEqualByComparingTo("-14400.00");
        assertThat(credit.getPaymentStatus()).isEqualTo("EMIS");
        String pdf = text(Files.readAllBytes(Path.of(credit.getPdfPath())));
        assertThat(pdf).contains("AVOIR").contains("annule la facture F-202611-01");
    }

    @Test
    void factureEmise_originalArchiveServiTelQuel() throws Exception {
        InvoiceEntity sent = existing("F-202611-01", "ENVOYEE");

        byte[] first = service.archivedPdf(sent);
        sent.setClientCompanyName("NOM MODIFIE APRES ENVOI");
        byte[] second = service.archivedPdf(sent);

        assertThat(second).isEqualTo(first);
        assertThat(text(second)).doesNotContain("NOM MODIFIE APRES ENVOI");
    }

    @Test
    void brouillon_toujoursRerendu() throws Exception {
        InvoiceEntity draft = existing("F-202611-01", "EN_ATTENTE");

        service.archivedPdf(draft);
        draft.setClientCompanyName("CLIENT CORRIGE");

        assertThat(text(service.archivedPdf(draft))).contains("CLIENT CORRIGE");
    }
}
