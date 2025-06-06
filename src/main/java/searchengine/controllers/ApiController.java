package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.exceptions.IndexingAlreadyRunningException;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Map<String, Object>>  startIndexing() {
        try {
            indexingService.startIndexing();
            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            return ResponseEntity.ok(response);
        } catch (IndexingAlreadyRunningException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Восстанавливаем флаг прерывания
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Процесс индексации был прерван");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        try {
            Map<String, Object> responce = indexingService.stopIndexing();
            return ResponseEntity.ok(responce);
        } catch (Exception e) {
            Map<String, Object> responce = new HashMap<>();
            responce.put("result", false);
            responce.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(responce);
        }

    }

}
