package com.example.demo.filecenter.dto;

import java.util.List;

public record FileCenterDetail(FileCenterItem item, String purposeCode, String relatedType,
                               Long relatedId, String relatedName, String note,
                               boolean manualUpload, boolean organized,
                               List<FileCenterActivityView> activities, FileAccountDraft accountDraft) {
}
