package com.example.demo.filecenter.dto;

import java.time.LocalDateTime;

public record FileCenterActivityView(String type, String title, String detail, LocalDateTime createTime) {
}
