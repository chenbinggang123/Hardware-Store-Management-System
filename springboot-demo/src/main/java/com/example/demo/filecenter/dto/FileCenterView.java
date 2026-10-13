package com.example.demo.filecenter.dto;

import java.util.List;

public record FileCenterView(
        List<FileCenterItem> items,
        int total,
        int pending,
        int completed,
        int failed) {
}
