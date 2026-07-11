package io.multiagent.invoice.service;

import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.model.SubscriptionInvoiceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionInvoiceService — factures d'abonnement SaaS")
class SubscriptionInvoiceServiceTest {

    @Mock InvoiceService invoiceService;

    @InjectMocks SubscriptionInvoiceService service;

    private SubscriptionInvoiceRequest request(String offer, String period, Integer count, BigDecimal negotiated) {
        return new SubscriptionInvoiceRequest(
                "ACME Consulting", "12 rue de la Paix, Paris", "RCS Paris 123",
                offer, count, period, LocalDate.of(2026, 8, 1),
                negotiated, null, LocalDate.of(2026, 7, 11), null);
    }

    private ArgumentCaptor<SimpleInvoiceRequest> mockGenerate() throws Exception {
        ArgumentCaptor<SimpleInvoiceRequest> captor = ArgumentCaptor.forClass(SimpleInvoiceRequest.class);
        when(invoiceService.generate(any(SimpleInvoiceRequest.class), anyString()))
                .thenAnswer(inv -> new InvoiceService.GeneratedInvoiceFiles(
                        inv.getArgument(0), Path.of("f.pdf"), Path.of("f.xlsx"), new byte[0], new byte[0]));
        return captor;
    }

    @Test
    @DisplayName("Croissance annuel : 49€ x 12 mois x -20% = 470,40€/consultant, échéance +30j, clause L441-10")
    void croissanceAnnuelAppliesDiscount() throws Exception {
        var captor = mockGenerate();
        service.generate(request("CROISSANCE", "ANNUEL", 12, null));

        verify(invoiceService).generate(captor.capture(), anyString());
        SimpleInvoiceRequest inv = captor.getValue();
        assertThat(inv.unitPriceHt()).isEqualByComparingTo("470.40");
        assertThat(inv.daysCount()).isEqualTo(12.0);
        assertThat(inv.invoiceName()).isEqualTo("ABO-202608-ACMECONSULTI");
        assertThat(inv.invoiceTitle()).contains("Croissance");
        assertThat(inv.paymentDueDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(inv.latePaymentClause()).contains("L441-10").contains("40 EUR");
        assertThat(inv.notes()).contains("01/08/2026").contains("31/07/2027").contains("-20%");
        assertThat(inv.sellerCompanyName()).isEqualTo("IA-INSIGHT");
    }

    @Test
    @DisplayName("Essentiel mensuel : 29€ plein tarif sur 1 mois")
    void essentielMensuelFullPrice() throws Exception {
        var captor = mockGenerate();
        service.generate(request("ESSENTIEL", "MENSUEL", 5, null));

        verify(invoiceService).generate(captor.capture(), anyString());
        assertThat(captor.getValue().unitPriceHt()).isEqualByComparingTo("29.00");
    }

    @Test
    @DisplayName("Trimestriel : 3 mois prépayés sans remise")
    void trimestrielMultipliesByThree() throws Exception {
        var captor = mockGenerate();
        service.generate(request("CROISSANCE", "TRIMESTRIEL", 2, null));

        verify(invoiceService).generate(captor.capture(), anyString());
        assertThat(captor.getValue().unitPriceHt()).isEqualByComparingTo("147.00");
    }

    @Test
    @DisplayName("Enterprise : prix négocié appliqué (79€ x 12 x -20%)")
    void enterpriseUsesNegotiatedPrice() throws Exception {
        var captor = mockGenerate();
        service.generate(request("ENTERPRISE", "ANNUEL", 30, new BigDecimal("79")));

        verify(invoiceService).generate(captor.capture(), anyString());
        assertThat(captor.getValue().unitPriceHt()).isEqualByComparingTo("758.40");
    }

    @Test
    @DisplayName("validation : requêtes invalides rejetées avec message explicite")
    void validationRejectsInvalidRequests() {
        assertThatThrownBy(() -> service.generate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(new SubscriptionInvoiceRequest(
                " ", null, null, "CROISSANCE", 1, "ANNUEL", LocalDate.now(), null, null, null, null)))
                .hasMessageContaining("clientCompanyName");
        assertThatThrownBy(() -> service.generate(request(null, "ANNUEL", 1, null)))
                .hasMessageContaining("offer");
        assertThatThrownBy(() -> service.generate(request("PLATINE", "ANNUEL", 1, null)))
                .hasMessageContaining("Offre inconnue");
        assertThatThrownBy(() -> service.generate(request("ENTERPRISE", "ANNUEL", 1, null)))
                .hasMessageContaining("negotiatedMonthlyPriceHt");
        assertThatThrownBy(() -> service.generate(request("CROISSANCE", "ANNUEL", 0, null)))
                .hasMessageContaining("consultantCount");
        assertThatThrownBy(() -> service.generate(request("CROISSANCE", "HEBDO", 1, null)))
                .hasMessageContaining("billingPeriod");
        assertThatThrownBy(() -> service.generate(new SubscriptionInvoiceRequest(
                "ACME", null, null, "CROISSANCE", 1, "ANNUEL", null, null, null, null, null)))
                .hasMessageContaining("periodStart");
    }

    @Test
    @DisplayName("ajustement prorata : sièges ajoutés x prix mensuel effectif x mois restants")
    void seatAdjustmentComputesProrata() throws Exception {
        var captor = mockGenerate();
        service.generateSeatAdjustment("ACME Consulting", "12 rue de la Paix", "RCS 123",
                "CROISSANCE", 2, "ANNUEL", null, LocalDate.of(2027, 7, 10), 5);

        verify(invoiceService).generate(captor.capture(), anyString());
        SimpleInvoiceRequest inv = captor.getValue();
        assertThat(inv.unitPriceHt()).isEqualByComparingTo("196.00");   // 49 x 0.8 x 5 mois
        assertThat(inv.daysCount()).isEqualTo(2.0);
        assertThat(inv.invoiceName()).startsWith("ABO-ADJ-");
        assertThat(inv.invoiceTitle()).contains("Ajustement");
        assertThat(inv.notes()).contains("2 consultant(s)").contains("5 mois").contains("10/07/2027");
    }

    @Test
    @DisplayName("ajustement : mensuel sans remise, offre inconnue refusée")
    void seatAdjustmentEdgeCases() throws Exception {
        var captor = mockGenerate();
        service.generateSeatAdjustment("ACME", null, null, "ESSENTIEL", 1, "MENSUEL",
                null, LocalDate.of(2026, 12, 31), 1);
        verify(invoiceService).generate(captor.capture(), anyString());
        assertThat(captor.getValue().unitPriceHt()).isEqualByComparingTo("29.00");

        assertThatThrownBy(() -> service.generateSeatAdjustment("ACME", null, null,
                "PLATINE", 1, "ANNUEL", null, LocalDate.now(), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
