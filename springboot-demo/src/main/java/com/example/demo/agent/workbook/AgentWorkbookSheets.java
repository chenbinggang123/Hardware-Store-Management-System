package com.example.demo.agent.workbook;

import java.util.Set;

/** Names of the worksheets the system adds to every generated file, shared by writer and reader. */
public final class AgentWorkbookSheets {
    public static final String INFO = "单据信息";
    public static final String SOURCE = "附件原文";
    public static final String ABOUT = "生成说明";
    public static final String RECEIPT = "收据版式";
    public static final String NOTE_COLUMN = "核对说明";
    public static final String TOTAL_LABEL = "合计";
    public static final Set<String> SYSTEM_SHEETS = Set.of(SOURCE, ABOUT, RECEIPT);

    private AgentWorkbookSheets() {
    }
}
