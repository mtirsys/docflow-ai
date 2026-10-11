package docflow_backend.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DatabaseController {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/db-check")
    public Map<String, Object> databaseCheck() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        Long documentsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents", Long.class);

        return Map.of(
                "status", "UP",
                "database", database == null ? "" : database,
                "documentsCount", documentsCount == null ? 0 : documentsCount
        );
    }
}
