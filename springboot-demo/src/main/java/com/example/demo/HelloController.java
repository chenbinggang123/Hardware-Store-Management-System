package com.example.demo;

import com.example.demo.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello")
    public ApiResponse<String> hello() {
        return ApiResponse.ok("服务运行正常", "Hello, World!");
    }
}
