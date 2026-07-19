package io.multiagent.invoice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Trace d'une relance de facture impayée (anti-doublon par palier + preuve d'envoi). */
@Entity
@Table(name = "invoice_dunning_log")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceDunningLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "invoice_name", length = 120)
    private String invoiceName;

    /** 1 = R1 rappel · 2 = R2 ferme · 3 = R3 mise en demeure */
    @Column(nullable = false)
    private int stage;

    @Column(length = 255)
    private String recipient;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();
}
