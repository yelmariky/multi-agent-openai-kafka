package io.multiagent.expense.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReceiptUploadResponse {
    private String id;
    private String originalFilename;
    private String binaryHash;
    private String textHash;
    private boolean binaryDuplicate;
    private boolean textDuplicate;
    private String duplicateOfBinary;
    private String duplicateOfText;
    private String ocrText;
    private String extractedExpenseJson;
    /** false si aucune dépense plausible n'a pu être extraite du document (voir {@link #reason}). */
    @Builder.Default
    private boolean success = true;
    /** Message explicatif quand success=false — affiché à l'utilisateur. */
    private String reason;
}
