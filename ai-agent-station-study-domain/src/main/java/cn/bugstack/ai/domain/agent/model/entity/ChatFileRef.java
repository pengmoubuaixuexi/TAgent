package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A file or artifact the user attached to a chat message.
 *
 * <p>Images keep using {@link ChatImageRef}. Only metadata of this reference is
 * stored with ChatMemory; extracted text stays in the attachment store and is
 * read on demand.</p>
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatFileRef {

    public static final String KIND_UPLOAD = "UPLOAD";
    public static final String KIND_ARTIFACT = "ARTIFACT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PURGED = "PURGED";
    public static final String PARSE_PENDING = "PENDING";
    public static final String PARSE_READY = "READY";
    public static final String PARSE_FAILED = "FAILED";

    private String attachmentId;

    /** UPLOAD or ARTIFACT. */
    private String kind;

    /** PENDING (uploaded, not sent yet), ACTIVE or PURGED. */
    private String status;

    private String fileName;

    private String mimeType;

    /** TEXT, CODE, MARKDOWN, CSV, SHEET, PDF, DOCUMENT or SLIDES. */
    private String fileKind;

    private Long size;

    private String sha256;

    private String artifactId;

    private Integer artifactRevision;

    /** MARKDOWN, TABLE or CODE when this references an artifact. */
    private String artifactFormat;

    /** Agent whose run produced the referenced artifact; set when the reference is bound, never stored. */
    private String sourceAgentId;

    private String sourceAgentName;

    private String parseStatus;

    private String parseError;

    /** LINE, PAGE, SLIDE or ROW. */
    private String unit;

    private Integer unitCount;

    private Long textChars;

    private boolean truncated;

    /** One-line structure description, e.g. sheet names with row counts. */
    private String summary;

    private String previewText;

    /** Full extracted text when it is small enough to place in context; never stored in ChatMemory. */
    private String inlineText;
}
