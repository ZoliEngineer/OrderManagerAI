package com.juzo.ai.ordermanager.advisor.web;

import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public sign-in settings for the static chat page, so the page needs no build step. */
@RestController
public class UiConfigController {

    private final AdvisorProperties.Ui ui;

    public UiConfigController(AdvisorProperties props) {
        this.ui = props.ui();
    }

    @GetMapping("/config.json")
    public AdvisorProperties.Ui config() {
        return ui;
    }
}
