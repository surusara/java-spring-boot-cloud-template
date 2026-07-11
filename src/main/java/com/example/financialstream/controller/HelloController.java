package com.example.financialstream.controller;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@Validated
public class HelloController {

    @GetMapping("/hello")
    public String hello(
            @RequestParam(defaultValue = "World")
            @Size(max = 100, message = "name must be at most 100 characters")
            @Pattern(regexp = "[\\p{Alnum} .,'\\-]*", message = "name contains invalid characters")
            String name) {
        return "HI " + HtmlUtils.htmlEscape(name) + ", How are you?";
    }
}
