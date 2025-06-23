package searchengine.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.IndexingServiceImpl;
import searchengine.services.StatisticsService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Slf4j
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    public ApiController(StatisticsService statisticsService, IndexingServiceImpl indexingServiceImpl, IndexingService indexingService) {
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
            // Проверяем, не запущен ли процесс индексации
            if (indexingService.isIndexingRunning()) {
                Map<String, Object> response = new HashMap<>();
                response.put("result", false);
                response.put("error", "Индексация уже запущена");
                return ResponseEntity.badRequest().body(response);
            }

            // Запускаем индексацию в отдельном потоке
            CompletableFuture.runAsync(() -> {
                try {
                    indexingService.startIndexing();
                } catch (Exception e) {
                    log.error("Ошибка при запуске индексации: " + e.getMessage());
                }
            });

            // Немедленно возвращаем успешный ответ
            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Обработка других исключений
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Произошла ошибка при запуске индексации: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        try {
            if (!indexingService.isIndexingRunning()) {
                Map<String, Object> response = new HashMap<>();
                response.put("result", false);
                response.put("error", "Индексация не запущена");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> response = indexingService.stopIndexing();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

    }

}
