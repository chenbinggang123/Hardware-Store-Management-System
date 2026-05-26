package com.example.demo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 列转换工具
 */
public final class JsonColumnSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonColumnSupport() {
    }

    public static <T> String writeList(List<T> items) {
        try {
            return OBJECT_MAPPER.writeValueAsString(items == null ? new ArrayList<>() : items);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("明细序列化失败", exception);
        }
    }

    public static <T> List<T> readList(String json, TypeReference<List<T>> typeReference) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("明细反序列化失败", exception);
        }
    }
}
