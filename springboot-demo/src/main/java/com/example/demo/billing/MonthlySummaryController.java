package com.example.demo.billing;

import com.example.demo.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MonthlySummaryController {
    private final MonthlySummaryService service;

    public MonthlySummaryController(MonthlySummaryService service) {
        this.service = service;
    }

    @GetMapping("/reports/monthly-summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(required = false) String month) {
        return ApiResponse.ok("月度汇总", service.summary(month));
    }
}
