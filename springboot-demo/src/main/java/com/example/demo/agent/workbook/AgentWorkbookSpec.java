package com.example.demo.agent.workbook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * What the model asks the system to turn into an Excel file. The model only describes
 * the table; limits, number parsing, arithmetic verification and rendering stay in Java.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWorkbookSpec {
    private String title;
    /** TABLE (default) or RECEIPT: paper receipt plus a linked, editable detail sheet. */
    private String layout;
    private Long attachmentId;
    private List<SheetSpec> sheets = new ArrayList<>();
    private List<InfoEntry> info = new ArrayList<>();
    private Boolean includeSourceText;
    private String note;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SheetSpec {
        private String name;
        private List<ColumnSpec> columns = new ArrayList<>();
        /** Each row is either a list of cell values or an object keyed by column name. */
        private List<Object> rows = new ArrayList<>();
        private List<String> totalColumns = new ArrayList<>();
        private String note;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnSpec {
        private String name;
        /** TEXT, NUMBER, INTEGER, MONEY or DATE. Missing values are inferred from the role or header. */
        private String type;
        /** NONE, QUANTITY, UNIT_PRICE or AMOUNT. Rows with all three roles are verified by the system. */
        private String role;

        public ColumnSpec() {
        }

        public ColumnSpec(String name, String type, String role) {
            this.name = name;
            this.type = type;
            this.role = role;
        }

        /** Lets the model send a plain header string instead of an object. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ColumnSpec fromName(String name) {
            return new ColumnSpec(name, null, null);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InfoEntry {
        private String label;
        private String value;

        public InfoEntry() {
        }

        public InfoEntry(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }
}
