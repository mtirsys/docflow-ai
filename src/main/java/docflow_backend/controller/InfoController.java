package docflow_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InfoController {

    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of(
                "project", "docflow-backend",
                "version", "0.0.1-SNAPSHOT",
                "description", "Document workflow backend service"
        );
    }
}
